# B-05 验证记录：告警出口「真实收到」+ 生产环境「拒启」

- 日期：2026-11-11 14:22–14:25
- 环境：本机 Docker Desktop；监控栈 `codejudge-monitoring`（Prometheus / Alertmanager 0.27.0 / alert-sink）
- 整改文件：`deploy/monitoring/alertmanager/entrypoint.sh`、`alertmanager.yml.tpl`、`templates/`、`webhook-sink.py`、`docker-compose.monitoring.yml`
- 原始缺口：默认 `receiver: 'null'`，告警只出现在 Alertmanager UI，SLO 违规不产生任何通知

---

## 1. 通道选择

选用 **本地 webhook 接收端**（`deploy/monitoring/alertmanager/webhook-sink.py`，容器 `codejudge-alert-sink`），原因：

- 本机无外网邮件中继（SMTP 需要真实账号/授权码，且本轮不应把口令写进仓库或命令）；
- 企业微信/钉钉机器人必须经转换器（Alertmanager 原生 webhook 报文格式不兼容），本轮不引入中间层；
- sink 是「HTTP 出口是否真的把告警发出去」最直接的证据：它把收到的**原始报文**打印到 stdout。

为不改动正在运行的 Alertmanager（当前 `.env` 为 `DEFAULT=email` / `CRITICAL=email`，用于生产形态），本轮另起一个**一次性探针实例**：

```powershell
docker run -d --name codejudge-am-b05probe --network codejudge-monitoring_default -p 19093:9093 `
  -v "D:/1/CodeJudge/deploy/monitoring/alertmanager/alertmanager.yml.tpl:/etc/alertmanager/alertmanager.yml.tpl:ro" `
  -v "D:/1/CodeJudge/deploy/monitoring/alertmanager/entrypoint.sh:/etc/alertmanager/entrypoint.sh:ro" `
  -v "D:/1/CodeJudge/deploy/monitoring/alertmanager/templates:/etc/alertmanager/templates:ro" `
  -e ALERTMANAGER_ENV=dev -e ALERTMANAGER_DEFAULT_RECEIVER=webhook -e ALERTMANAGER_CRITICAL_RECEIVER=webhook `
  -e ALERTMANAGER_WEBHOOK_URL=http://alert-sink:8080/alerts -e ALERTMANAGER_WECOM_WEBHOOK=http://127.0.0.1:1/disabled `
  -e ALERTMANAGER_SMTP_SMARTHOST=localhost:25 -e ALERTMANAGER_SMTP_FROM=alertmanager@codejudge.invalid `
  -e ALERTMANAGER_EMAIL_TO=alerts@codejudge.invalid -e ALERTMANAGER_EXTERNAL_URL=http://localhost:19093 `
  --entrypoint /bin/sh prom/alertmanager:v0.27.0 /etc/alertmanager/entrypoint.sh
```

启动日志（截取）：

```
[entrypoint] 配置已渲染 -> /alertmanager/rendered-alertmanager.yml
[entrypoint] 默认接收器 = webhook ｜ critical = webhook
msg="skipping creation of receiver not referenced by any route" receiver=wecom
msg="Listening on" address=[::]:9093
```

## 2. 真实送达：触发 → 接收端收到

触发（critical 路由 `group_wait: 10s`）：

```powershell
$body = '[{"labels":{"alertname":"B05ChannelProbe","severity":"critical","instance":"localhost","team":"qa"},'
      + '"annotations":{"summary":"B-05 alert delivery probe","description":"synthetic alert from B-05 verification"}}]'
Invoke-WebRequest -Uri http://localhost:19093/api/v2/alerts -Method Post -ContentType 'application/json' -Body $body -UseBasicParsing
# StatusCode = 200
```

等待 25s 后，**接收端**（`codejudge-alert-sink`，非 Alertmanager UI）的原始收件记录：

```
[alert-sink] POST /alerts bytes=816 alerts=[('B05ChannelProbe', 'firing', 'critical')]
[alert-sink] body={"receiver":"webhook","status":"firing","alerts":[{"status":"firing","labels":{"alertname":"B05ChannelProbe","instance":"localhost","severity":"critical","team":"qa"},"annotations":{"description":"synthetic alert from B-05 verification","summary":"B-05 alert delivery probe"},"startsAt":"2026-11-11T06:23:19.878093511Z","endsAt":"0001-01-01T00:00:00Z","generatorURL":"","fingerprint":"7d4f433cc74a1491"}],"groupLabels":{"alertname":"B05ChannelProbe"},"commonLabels":{...},"externalURL":"http://localhost:19093","version":"4","groupKey":"{}/{severity=\"critical\"}:{alertname=\"B05ChannelProbe\"}","truncatedAlerts":0}
```

Alertmanager 侧 `GET /api/v2/alerts`（v0.27 无 `/-/alerts` 资源，返回 404）：

```json
[{"annotations":{"description":"synthetic alert from B-05 verification","summary":"B-05 alert delivery probe"},
  "endsAt":"2026-11-11T06:28:19.878Z","fingerprint":"7d4f433cc74a1491",
  "receivers":[{"name":"webhook"}],"startsAt":"2026-11-11T06:23:19.878Z",
  "status":{"inhibitedBy":[],"silencedBy":[],"state":"active"},
  "updatedAt":"2026-11-11T06:23:19.878Z",
  "labels":{"alertname":"B05ChannelProbe","instance":"localhost","severity":"critical","team":"qa"}}]
```

结论：告警**离开了 Alertmanager 进程**并被独立进程接收（816 字节原始报文），不是"UI 里能看到"。

## 3. prod 拒启：`ALERTMANAGER_ENV=prod` + `receiver=null`

```powershell
docker run -d --name codejudge-am-b05prod ... -e ALERTMANAGER_ENV=prod `
  -e ALERTMANAGER_DEFAULT_RECEIVER=null -e ALERTMANAGER_CRITICAL_RECEIVER=null ...
docker inspect codejudge-am-b05prod --format 'status={{.State.Status}} exit={{.State.ExitCode}}'
docker logs codejudge-am-b05prod
```

输出：

```
status=exited exit=1

[entrypoint] 配置已渲染 -> /alertmanager/rendered-alertmanager.yml
[entrypoint] 默认接收器 = null ｜ critical = null
[entrypoint] FATAL: ALERTMANAGER_DEFAULT_RECEIVER=null —— 生产环境告警没有出口（只进 UI，等于没有告警）。
[entrypoint]        改用 email 或 webhook；确属临时排查请显式设 ALERTMANAGER_ENV=dev。
```

结论：**退出码 1**，进程从未监听 9093 —— 生产形态下"没有出口却显示健康"的静默失效被堵死。

## 4. dev 行为：同配置去掉 `prod`

```powershell
docker run -d --name codejudge-am-b05dev -p 19094:9093 ... `
  -e ALERTMANAGER_DEFAULT_RECEIVER=null -e ALERTMANAGER_CRITICAL_RECEIVER=null ...
```

输出：

```
status=running exit=0
[entrypoint] 默认接收器 = null ｜ critical = null
[entrypoint] WARN: ALERTMANAGER_DEFAULT_RECEIVER=null —— 告警不会外发（dev 允许；生产必须接真实通道）
[entrypoint] WARN: ALERTMANAGER_CRITICAL_RECEIVER=null —— 告警不会外发（dev 允许；生产必须接真实通道）
msg="Listening on" address=[::]:9093
```

## 5. preflight F1 判据

已追加到 `docs/LAUNCH-CHECKLIST.md` 的 A2 条目下方（可勾选 4 行）：**触发 → 期望收到（webhook 日志 / email 计数器）→ 90s 超时判 FAIL → 收尾确认 prod 非 null**。

## 6. 收尾

- 三个探针容器（`codejudge-am-b05probe` / `-b05prod` / `-b05dev`）已全部 `docker rm -f`；
- 正在运行的 `codejudge-alertmanager` **未改动**（仍为 `.env` 指定的 `email`/`email`），无需恢复；
- 未在仓库或本文件中写入任何真实 webhook / token / 邮箱口令；本文出现的 `alertmanager@codejudge.invalid` 为 RFC 2606 占位域名。
