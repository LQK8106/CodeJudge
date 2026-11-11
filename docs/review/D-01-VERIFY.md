# D-01 验证记录：requestId 跨 MQ 透传（gateway → submission → MQ → worker）

- 日期：2026-11-11 14:19–14:22
- 环境：本机 docker compose 全栈（gateway:9080 / auth:9081 / submission / worker / RocketMQ 4.9.7 broker-a）
- 整改代码：`RequestIdContext`、`RocketMQTemplate`（投递写 `requestId` 用户属性）、
  `RocketMQConsumerContainer#handleOne`（回填 MDC + `finally` 恢复）、`RequestIdGlobalFilter`（网关生成/透传 + 响应头回写）
- 原始缺口：worker 消费侧日志全部为 `[req=]` 空值

---

## 1. 正向链路：同一次提交在三个服务命中同一 id

采集方式：经网关登录学员种子账号 → `POST /submissions`（problemId=4001，Python AC 代码）→ 从**响应头**取 `requestId`。

```
login status = 200 requestId-header = 5fd41cc5d6f1472387c72cd1ca282fb6
submit status = 200 requestId = f1937e3b174e458881e867869d06d251 submissionId = 2120400942868303873
  poll: 200 SUCCESS AC
```

（客户端没有自带 requestId，id 由网关 `RequestIdGlobalFilter` 生成并回写响应头。）

按 id 在三个服务日志中检索：

```powershell
docker logs codejudge-judge-gateway-1     | Select-String -SimpleMatch f1937e3b174e458881e867869d06d251
docker logs codejudge-judge-submission-1  | Select-String -SimpleMatch f1937e3b174e458881e867869d06d251
docker logs codejudge-judge-worker        | Select-String -SimpleMatch f1937e3b174e458881e867869d06d251
```

命中数：**gateway 1 条 / submission 5 条 / worker 7 条**（≥3 条达标）。原始日志见
`docs/review/raw-d01-20261111/{gateway,submission,worker}-hits.log`。

关键行（已去 ANSI 色码）：

```
# gateway
2026-11-11 14:19:13.221 INFO [reactor-http-epoll-8] [req=f1937e3b174e458881e867869d06d251] c.c.gateway.filter.RequestIdGlobalFilter : 请求进入：POST /submissions

# submission
2026-11-11 14:19:14.606 INFO [tomcat-handler-670] [req=f1937e3b174e458881e867869d06d251] c.c.submission.service.SubmissionService : 提交落库成功 submissionId=2120400942868303873 ...
2026-11-11 14:19:14.658 INFO [tomcat-handler-670] [req=f1937e3b174e458881e867869d06d251] c.codejudge.common.mq.RocketMQTemplate : 发送 MQ 消息：topic=judge_submission, tag=CREATED, ok=true
2026-11-11 14:19:17.037 INFO [ConsumeMessageThread_cj_submission_result_consumer_4] [req=f1937e3b174e458881e867869d06d251] c.c.submission.mq.ResultEventHandler : 判题结果事件：submissionId=2120400942868303873 verdict=AC score=100 ...

# worker（消费侧 id 非空）
2026-11-11 14:19:14.792 INFO [ConsumeMessageThread_cj_worker_consumer_4] [req=f1937e3b174e458881e867869d06d251] c.c.worker.mq.SubmissionTaskHandler : 收到判题任务：submissionId=2120400942868303873 taskId=2120400942956384258 attempt=0 source=CREATED ...
```

结论：**同一个 id**（`f1937e3b174e458881e867869d06d251`）出现在网关入口、submission 落库/投递、worker 消费三处；worker 侧 `[req=]` 不再是空值。
注意 submission 侧「判题结果事件」是 worker 回投 RESULT 消息后被**同一 id 回填**的消费日志，可见 id 在 MQ 往返中保持。

## 2. 旧消息兼容：无 `requestId` 用户属性的消息

构造方式：在 broker 容器内用 `mqadmin sendMessage` 直接投递一条**不带用户属性**的最小消息（绕过应用生产端）：

```powershell
docker cp $env:TEMP\d01_old_msg.json codejudge-mq-broker:/tmp/d01.json
docker exec codejudge-mq-broker sh /tmp/send2.sh
# send2.sh: sh mqadmin sendMessage -n rocketmq-namesrv:9876 -t judge_submission -c CREATED -p "$(cat /tmp/d01.json)"
# body: {"taskId":999999999999,"submissionId":999999999999,"attempt":0,"source":"OLD_NO_RID"}
```

发送结果：

```
#Broker Name                      #QID  #Send Result            #MsgId
broker-a                          3     SEND_OK                 7F00000101F10F6F4D3334DBCCD00000
```

worker 侧消费输出（原始见 `docs/review/raw-d01-20261111/legacy-warn.log`）：

```
2026-11-11 14:20:20.079 WARN [ConsumeMessageThread_cj_worker_consumer_1] [req=mq-13f04d4b86934fa9b107a362076c45e2] c.c.common.mq.RocketMQConsumerContainer : 消息未携带有效 requestId，已生成兜底 id=mq-13f04d4b86934fa9b107a362076c45e2：topic=judge_submission, tag=CREATED, msgId=7F00000101F10F6F4D3334DBCCD00000
2026-11-11 14:20:20.095 WARN [ConsumeMessageThread_cj_worker_consumer_1] [req=mq-13f04d4b86934fa9b107a362076c45e2] c.c.worker.mq.SubmissionTaskHandler : 任务不存在，丢弃消息：taskId=999999999999（提交可能已被清理）
```

结论：**WARN + 生成 `mq-` 前缀兜底 id + 消息被正常消费**（handler 走完并 ack，无异常、无重投；body 里的 taskId 是不存在的哨兵值，属预期的业务侧丢弃）。

## 3. MDC 不串号（`finally` 清理生效）

连续投递多条无属性消息后，按「消费线程 → requestId」配对，可看到**同一条消费线程先后处理的两条消息拿到了不同 id**：

```
cj_worker_consumer_1  mq-13f04d4b86934fa9b107a362076c45e2
cj_worker_consumer_2  mq-c78ba37f9eba45e3942242211f3c18ec
cj_worker_consumer_4  mq-3c794ced070a463a888b73bfa689dda2
cj_worker_consumer_1  mq-db0876442c854523a21c0a43f658dd2d   ← 同一线程，未继承上一条的 mq-13f0…
cj_worker_consumer_2  mq-abe16101e8e645eb9501ccc10eb91904   ← 同一线程，未继承 mq-c78b…
cj_worker_consumer_3  mq-a92a1bfb55424dce8c0ca92f019a2c55
```

同时，上述 `mq-` 消息没有任何一条继承真实提交的 `f1937e3b…`，说明 `handleOne` 的 `finally RequestIdContext.restore(previous)` 在消费线程复用时生效。

## 4. 覆盖范围与断点（不声称全链路打通）

**已覆盖（实测）**：

- 网关入口（`RequestIdGlobalFilter`）：生成/透传，写响应头 `requestId`，注入下游请求头；
- submission：`RequestIdInterceptor` 沿用网关注入的 id，日志、落库、MQ 投递共用；
- MQ：生产端写 `requestId` 用户属性，消费端回填 MDC（实测同一 id 出现在 submission 的结果消费链与 worker 的判题消费链；contest 等其他消费方走同一 `RocketMQConsumerContainer`，本轮未单独取证）；
- 无 HTTP 上下文的入口显式生成带前缀 id：`cmp-`（补偿重投）、`cron-`（定时对账）、`hb-`（worker 心跳）、`mq-`（旧消息兜底）。

**仍存在的断点**：

- **沙箱内进程**：判题实际在 `docker run` 起的沙箱容器里执行，其中的编译/运行进程看不到 `requestId`（未注入环境变量），worker 只能把「沙箱启动/结束」记在自己的链上；
- **网关的 Reactor 异步日志**：`MDC` 不会随 Reactor 线程切换自动传播，网关只在同步入口日志写 `[req=]`，异步段依赖响应头与下游头传递；
- **服务内部自建线程池**（如 WebSocket 推送、异步回执）：未显式 `RequestIdContext.open(...)` 的任务不会继承调用方 id。

## 5. 证据文件

- `docs/review/raw-d01-20261111/submit-probe.log`（提交与轮询输出）
- `docs/review/raw-d01-20261111/gateway-hits.log` / `submission-hits.log` / `worker-hits.log`（同 id 命中行）
- `docs/review/raw-d01-20261111/legacy-warn.log`（旧消息 WARN 与处理结果）
