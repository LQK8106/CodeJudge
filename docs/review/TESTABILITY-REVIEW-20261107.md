# 设计文档可测性审查报告（2026-11-07）

> 范围：`docs/` 下的设计文档（ARCHITECTURE / ADR-001 / SLO / API-REFERENCE / DEPLOYMENT / LAUNCH-CHECKLIST）。
> 方法：**只读文档 + 实测证据对标**，不改业务代码；每条结论都能被另一人顺着文档定位与给出的断言复现。
> 统计：5 个类别共 **26 项**（接口契约 6｜异常降级 5｜数据一致性与幂等 5｜可观测性 5｜发布回滚 5）。
> 判定口径：**「文档缺失」= 设计已存在但没写清/写错，补文档即可测；「设计缺失」= 文档写了但机制本身没有可判定口径，需要补机制。**
> 分级口径：**高 = 直接导致线上安全/数据正确性/全站可用性风险，或让关键链路无法验收；中 = 单条链路可测性不足、需要补口径才能自动化；低 = 体验/维护性问题，不影响核心验收。**

## ⚠️ 闭环状态声明（2026-11-08 补记）

> **截至 2026-11-08，本报告的 26 项发现均未逐条验证处置状态，也没有对应的变更提交、发布记录或回归验证证据。**
>
> 1. 本报告的性质是**只读审查 + 发现记录**，不是处置清单；文中所有「可测化建议」都是**待办建议**，不代表已经实施。
> 2. 引用本报告时，**不得声称这些问题已经解决、已经关闭或已经实施**；只能表述为「发现 N 项可测性缺口，待验证、待处置」。
> 3. 若某项确实已被处置，必须有独立的验证证据（提交号 + 对应断言/命令的执行结果），并在此处单独登记；本声明不代替逐项验证。

## ✅ 已处置登记（2026-11-11）

> 以下 6 项（本报告判为「高」的 6 条）均已按「代码/配置整改 + 实跑证据 + 独立验证记录」处置完毕。
> 每条都能按「验证命令」复跑，验证记录里的数字均为当日实测，不是推断。

| 编号 | 处置提交 | 验证记录 | 验证命令（可复跑） | 结论摘要 |
|---|---|---|---|---|
| A-05 | `a0e2b44` | [A-05-VERIFY.md](./A-05-VERIFY.md) | `curl -s -o /dev/null -w '%{http_code}' localhost:9080/jwks`；`py -3 $env:TEMP\a05_burst.py` | `/jwks` **404**；篡改签名 **401**（原 token 200 对照）；并发 12 时 5 放行 / 7 个 **HTTP 429**，静默 4s 后登录 200 |
| B-05 | `86fb703` | [B-05-VERIFY.md](./B-05-VERIFY.md) | `docker run ... -e ALERTMANAGER_ENV=prod -e ALERTMANAGER_DEFAULT_RECEIVER=null` 后 `docker inspect --format '{{.State.ExitCode}}'` | prod + `receiver=null` **exit 1 拒启**；dev 同配置 WARN 放行；真实投递被 `alert-sink` 收到（816 字节原始报文） |
| C-04 | `54eabfc` | [C-04-VERIFY.md](./C-04-VERIFY.md) | `SELECT count(*) FROM ai_review WHERE status=1 AND length(btrim(content))=0;`；`mvn -pl judge-ai test -Dtest=AiReviewRepositoryTest` | V2 于 **2026-11-10 16:01:37** 执行并订正 98 行；巡检验收 **0 行**；CHECK 约束在库；降级单测 **4/4 PASS** |
| D-01 | `482e896` | [D-01-VERIFY.md](./D-01-VERIFY.md) | 提交一次后按响应头 `requestId` 分别 `docker logs` gateway / submission / worker | 同一 id 命中 **1 / 5 / 7** 条；无属性旧消息 WARN + `mq-` 兜底且被正常消费；同线程两条消息 id 不继承 |
| E-01 | `8d37ae7` | [E-01-KEY-ROTATION-DRILL.md](./E-01-KEY-ROTATION-DRILL.md) | `mvn -pl judge-auth,judge-gateway -am test`；`py -3 scripts/verify-p1-login.py` | 灰度四步无 401 窗口；单测 auth **66** / gateway **28**；登录回归 **43/43**；算法表述已改为「48 字节熵 → base64 64 字符 → HS512」 |
| E-04 | `4da283a` | [E-04-VERIFY.md](./E-04-VERIFY.md) | 同一镜像 + `--env-file .env` 覆写 `CJ_TOKEN_CONTRACT_VERSION` / `GW_AUTH_URI` 起一次性容器 | 版本不一致**双向**均 exit 1（v2vs v1 / v1 vs v2）；不可达严格拒启、宽松 WARN；`enabled=false` 跳过；回滚口径见 DEPLOYMENT |


## 0. 结论摘要

| 类别 | 检查项 | 高 | 中 | 低 |
|---|---|---|---|---|
| 接口契约可测性 | 6 | 1 | 4 | 1 |
| 异常与降级路径 | 5 | 1 | 3 | 1 |
| 数据一致性与幂等 | 5 | 1 | 3 | 1 |
| 可观测性 | 5 | 1 | 1 | 3 |
| 发布与回滚风险 | 5 | 2 | 2 | 1 |
| **合计** | **26** | **6** | **13** | **7** |

- 文档缺失 **15 项**（补文档即可测），设计缺失 **11 项**（需补机制）。
- 高优先级缺口 6 项：A-05（JWT 算法两份文档冲突）、B-05（告警出口为 null）、C-04（成功态空正文）、D-01（requestId 跨 MQ 断链）、E-01（换钥全站 401）、E-04（发布顺序无启动期校验）。
- 每条包含：文档定位（可点击跳转）→ 文档现状 → 为什么测不了/会漏掉什么 → 判定（含分级）→ 可测化建议（具体断言或命令）。

---

## 1. 接口契约可测性（6 项）

| # | 级别 | 文档定位 | 文档现状 | 为什么测不了 / 会漏掉什么 | 判定 | 可测化建议 |
|---|---|---|---|---|---|---|
| A-01 | 中 | [API-REFERENCE.md#L42](../API-REFERENCE.md#L42) | 分页响应示例写成 `{"code": 1, ...}`，而同文件 [L19](../API-REFERENCE.md#L19) 明确「`code=200` 为成功（不是 1）」 | 按示例写断言的工具会全红，按 L19 写的会全绿——同一文档两种口径，CI 无法作为唯一事实来源 | 文档缺失 | 分页示例改 `code: 200`，并在 CI 加一条「文档示例可执行」检查（把示例 JSON 跑一遍断言） |
| A-02 | 中 | [API-REFERENCE.md#L273](../API-REFERENCE.md#L273) | 提交响应示例同样写成 `"code": 1` | 与 A-01 同型；提交是最高频接口，契约错一行会污染压测与前端拦截器断言 | 文档缺失 | 同上；并把该示例纳入 [DEPLOYMENT.md#L74](../DEPLOYMENT.md#L74) 提到的 preflight 断言集 |
| A-03 | 中 | [API-REFERENCE.md#L356](../API-REFERENCE.md#L356) | SSE `DELTA` 事件写 `data: {"text":"..."}` | 实现推送的字段是 `content`（`perf-test/measure-ai-ttft.py` 按 `ev["content"]` 取正文；本次评测探针同样只能读 `content`）。按文档写客户端会一直拿到空正文 | 文档缺失（实现正确、文档错） | 契约改为 `{"content": "..."}`；并用一条 SSE 契约测试固定字段名（断言首帧含 `content` 且 `text` 不存在） |
| A-04 | 低 | [API-REFERENCE.md#L354](../API-REFERENCE.md#L354) | START 示例写 `"model":"glm-4"` | 实际 `CJ_LLM_MODEL` 已切换为 `glm-5.3-flash`（本次补齐语料 `model` 列全为该值）。示例与运行值不符，模型切换时无人知道契约是否变化 | 文档缺失 | 示例去掉固定模型名或标注「随 `CJ_LLM_MODEL` 变化」；断言 `model` 非空且与 `.env` 一致 |
| A-05 | **高** | [API-REFERENCE.md#L53](../API-REFERENCE.md#L53) 与 [ARCHITECTURE.md#L14](../ARCHITECTURE.md#L14)、[#L109](../ARCHITECTURE.md#L109) | API 写「令牌算法 HS256（HMAC 对称）」「`/jwks` 已随 2026-09-25 加固删除」；架构图与登录时序写「RS256 + JWKS」「RS256 签发」 | 验签算法决定安全用例形态（HS256 需共享密钥、RS256 需公钥轮换）。两份文档矛盾，安全测试无法写「令牌被篡改必须 401」之外的任何断言，也无法做密钥轮换演练 | 文档缺失（同系统两份文档冲突） | 以代码为准统一为一处描述；加断言：`GET /jwks` 返回 404、篡改签名的 token 返回 401、登录限流覆盖 `/accounts/login` |
| A-06 | 中 | [API-REFERENCE.md#L370](../API-REFERENCE.md#L370) | 断线重连写「请求头携带 `Last-Event-ID: <seq>`，按 `seq` 过滤」，但事件样例里的 seq 只出现在注释 `← seq=1`，**没有定义 seq 落在 SSE `id:` 字段还是 data 里** | 无法写重连用例：不知道客户端该记什么，也不知道服务端回放以哪个字段为准；断线重连是本系统唯一的「省 token」机制，测不了就只能靠人眼 | 文档缺失 | 明确「`id:` 字段 = seq（十进制）」；用例：订阅后在第 3 帧断开，带 `Last-Event-ID: 3` 重连，断言只回放 seq≥4 且不重新生成 |

> **A-05 验证入口**：三条断言（`/jwks` 404、篡改签名 401、登录限流 429）已于 2026-11-11 实跑，记录见 [A-05-VERIFY.md](./A-05-VERIFY.md)。

补充两项同类问题（不计入上表编号，属建议级）：

- [API-REFERENCE.md#L349](../API-REFERENCE.md#L349) 只写了 `reviewType` 的 1/2/3 语义，**没写 3（相似题推荐）的响应差异** → 该分支的自动化用例无从断言。
- [API-REFERENCE.md#L290](../API-REFERENCE.md#L290) 只写「学员调用必须 `maskHidden=true`」，**没写由谁强制**（网关 or judge-submission）→ 无法构造「学员传 `maskHidden=false`」的越权用例（能测的是结果，测不了责任边界）。

## 2. 异常与降级路径（5 项）

| # | 级别 | 文档定位 | 文档现状 | 为什么测不了 / 会漏掉什么 | 判定 | 可测化建议 |
|---|---|---|---|---|---|---|
| B-01 | 中 | [API-REFERENCE.md#L364](../API-REFERENCE.md#L364) | 只规定「订阅前失败 = HTTP 4xx + JSON；订阅后失败 = HTTP 200 + `event: ERROR`」，未定义 `ERROR` 事件内的 code 取值域 | 已知形态、未知语义：客户端只能「看到 ERROR 就报错」，无法针对「模型超时 / 检索失败 / 内容被拦截」做差异化重试；也测不出「什么算失败」 | 文档缺失 | 定义 `ERROR` 事件的 `code` 枚举（如 `LLM_TIMEOUT` / `RETRIEVAL_FAILED` / `QUOTA`），用例逐个注入并断言事件 code |
| B-02 | 中 | [ARCHITECTURE.md#L246](../ARCHITECTURE.md#L246) + [API-REFERENCE.md#L360](../API-REFERENCE.md#L360) | 决策写「SSE 错误走事件不走状态码」，但**没有心跳/空闲超时/最大时长的约定** | 半开连接（NAT 超时、代理掐断）无法被判定：不知道多久没事件算异常，测试只能靠人为 sleep，无法稳定复现 | 设计缺失 | 约定心跳事件（如 `event: PING` 每 15s）与最大时长；用例：注入代理 5min 静默，断言客户端在 N 个心跳周期内重连 |
| B-03 | 中 | [SLO.md#L38](../SLO.md#L38) | S2「提交受理 P99 < 200ms」的告警列写的是「**（无专用告警）**，经看板观测」 | 有 SLO、无告警 = 只有人看仪表盘才能发现违规；夜间/周末的受理退化不会被自动捕获 | 设计缺失 | 补一条 `SubmissionAcceptLatencyP99Breach` 规则（阈值 200ms，for: 5m），并纳入 [SLO.md#L35](../SLO.md#L35) 映射表 |
| B-04 | 低 | [SLO.md#L54](../SLO.md#L54) | 明确记录「上述指标/看板的**运行时验证**（依赖 Docker daemon 恢复 + compose app 实测，BLOCKED）」 | 度量本身没在容器化栈验证过 → S1/S3 的数值无法被信任；这也是本次能实测发现问题的切入点 | 文档缺失（验证缺口，非设计缺失） | 落地一条「指标存在性」冒烟：抓 `/-/ready` 与 `/actuator/prometheus`，断言 `http_server_requests_bucket` 与 `cj_judge_e2e_seconds_bucket` 均存在且非零 |
| B-05 | **高** | [LAUNCH-CHECKLIST.md#L14](../LAUNCH-CHECKLIST.md#L14) + [DEPLOYMENT.md#L505](../DEPLOYMENT.md#L505) | A2 记录「Alertmanager 路由指向 `null` receiver，告警不会发到任何地方」 | 告警规则语法正确、阈值也校准过，但**出口是断的**：所有 SLO 违规在真实故障时都不产生通知；「告警有效性」无验收判据 | 设计缺失（文档已承认） | 把「真实触发一条测试告警并确认收到」写成 preflight 的 F1 判据（文档已要求），并加一条定时演练（每周一次静默告警） |

降级路径另有一处需要显式定义（建议级）：

- AI 点评的 `degraded=true` 只出现在 START 事件（[API-REFERENCE.md#L354](../API-REFERENCE.md#L354)），**没有定义降级正文的质量下界与前端展示规则**。实测：库里有 3 条 `model='degraded'` 的 171 字模板正文，另有 12 条 `status=1` 但正文长度为 0 的记录 —— 说明「降级/空正文」在数据层没有可判定口径（详见 C-04）。

## 3. 数据一致性与幂等（5 项）

| # | 级别 | 文档定位 | 文档现状 | 为什么测不了 / 会漏掉什么 | 判定 | 可测化建议 |
|---|---|---|---|---|---|---|
| C-01 | 中 | [API-REFERENCE.md#L276](../API-REFERENCE.md#L276) | 幂等语义写「同一用户 + 同一题目 + 同一竞赛 + **同一代码哈希**」，但没定义哈希算法、是否含语言字段、是否归一化空白/换行 | 无法构造边界用例：把 `System.out.println(a+b);` 改成 `System.out.println(a + b);` 到底算不算「同一份代码」？答案取决于未公开的哈希实现 | 文档缺失 | 写出 codeHash 算法（如 SHA-256(UTF-8(code))，不含语言/空白归一化）；用例：同码二次提交 `idempotent=true`，改一个空格后 `idempotent=false` |
| C-02 | 中 | [ARCHITECTURE.md#L126](../ARCHITECTURE.md#L126) | 幂等快路径是 Redis key，兜底是 `uk_submission_idempotent` 唯一索引 | 两条防线不一致时（Redis 命中但 DB 记录已被清理 / Redis 未命中但 DB 已存在）行为没写：是返回旧记录、还是插库失败 500？测不了「Redis 挂掉后的幂等」 | 文档缺失 | 定义「Redis 不可用时降级为纯 DB 唯一索引」；用例：停 Redis → 同码提交两次 → 第二次仍 `idempotent=true` 且不新增行 |
| C-03 | 低 | [ARCHITECTURE.md#L243](../ARCHITECTURE.md#L243) | 双通道（Redis ZSet + RocketMQ）靠 `judge_task` 状态 CAS 收敛 | 文档只说「都幂等」，但没给可观测的验收项：同一 task 被执行两次时，外部能看到什么？只能靠「结果不重复」间接推断 | 文档缺失 | 加断言：同一 submission 的 `judge_result` 行数恒等于用例数、`judge_task.attempt` 不因双投递而重复加 1 |
| C-04 | **高** | 实测数据（`judge_ai.ai_review`） | `status=1` 表示成功，但实测存在 **`status=1` 且 `content` 长度为 0** 的记录：历史 12 条（id 45/50/53/57/63/64/71/76/78/81/89/91），本次补齐语料期间又新增 21 条，**累计 33 条**（2026-11-07 15:30 查询） | 「成功」与「有正文」是两件事却没有约束：上游把空正文判成成功，下游评测/展示会看到「生成完成但正文为空」。这是能直接被数据判死的契约缺口，且本次规模化复现（补齐语料时 32 条请求中 13 条写入空正文、4 条 HTTP 500 超时，重试又新增空正文记录） | 设计缺失（缺成功态校验） | 定义「`status=1` ⇒ `length(trim(content))>0`」，并在落库处加非空校验；补一条巡检 SQL（本次即用此查询发现） |
| C-05 | 中 | [ARCHITECTURE.md#L266](../ARCHITECTURE.md#L266) | 对账扫描「每 60s」「取最老一批（`RECONCILE_BATCH`）」，但没写 batch 默认值与单轮上界 | 恢复时间不可算：积压 10 万条时，一轮 60s 只能摘多少？「积压多久能自愈」没有数字，RTO 无法承诺 | 文档缺失 | 写出 `RECONCILE_BATCH` 默认值（如 500）与单轮耗时上界；断言：注入 5k 僵尸成员，N 分钟内 `queueBacklog` 归零 |

封榜一致性（建议级）：[ARCHITECTURE.md#L160](../ARCHITECTURE.md#L160) 写「封榜瞬间对每个用户 ZSet 做原子快照」，但**没有写快照部分失败时的回退**（Redis 写成功一半）。用例建议：封榜流程注入一次失败，断言榜单要么全冻结、要么全不冻结，不出现半冻结。

## 4. 可观测性（5 项）

| # | 级别 | 文档定位 | 文档现状 | 为什么测不了 / 会漏掉什么 | 判定 | 可测化建议 |
|---|---|---|---|---|---|---|
| D-01 | **高** | [ARCHITECTURE.md#L205](../ARCHITECTURE.md#L205) | 「`requestId` 透传：网关生成，全链路日志可串」 | **实测不成立**：`judge-worker` 消费侧日志全部是 `[req=]` 空值（本次判题日志可见），即 requestId 没有跨 MQ 传递。所谓「全链路可串」只到 MQ 投递为止，worker 之后的链路断链 | 设计缺失（可观测性缺口，实测证据） | 把 requestId 写进 MQ 消息头/消息体并在 worker MDC 恢复；断言：同一次提交在 gateway/submission/worker 日志中用同一 requestId 能检索到 ≥3 条 |
| D-02 | 中 | [SLO.md#L13](../SLO.md#L13) | S3 判题端到端用 `cj_judge_e2e_seconds`，口径写「submission→result 时间差」 | 未定义「终态」是否包含 CE/SE；实测 **CE 提交没有 `judge_result` 行**（本次 5 条 CE 记录的 `judge_result` 计数均为 0），若指标按 `judge_result` 写入点埋点，CE 的端到端耗时不会被统计 → S3 天然漏掉编译失败这一类 | 文档缺失 | 明确 S3 分母含 CE（以 submission 终态时间为准）；断言：一次 CE 提交后 `cj_judge_e2e_seconds_count` 增加 1 |
| D-03 | 低 | [SLO.md#L11](../SLO.md#L11) | S1 用 `http_server_requests{uri!~"/actuator.*"}` 的 5xx/总计数 | 没写**最小请求量门槛**：夜间低流量时 1 个 5xx 就能把 99.9% 打穿，月度预算被噪声触发 | 设计缺失 | 加分母门槛（如 5m 窗口 <10 req 不判违规）；断言：构造 1 个 5xx + 0 流量，不触发 `PlatformErrorBudgetBurn` |
| D-04 | 低 | [LAUNCH-CHECKLIST.md#L31](../LAUNCH-CHECKLIST.md#L31) | D6 日志滚动：「容器部署 → 日志驱动配 max-size；systemd → logrotate」，由用户按部署形态决定 | 代码层没有任何约束，也没有「保留多久」的约定 → 排障所需的判题日志可能被滚动覆盖，事故复盘拿不到证据 | 设计缺失 | 约定保留期（如 7 天）与单文件上限，写进 compose 的 `logging` 段；断言：`docker inspect` 输出含 `max-size`/`max-file` |
| D-05 | 低 | [SLO.md#L52](../SLO.md#L52) | 错误预算看板由 `gen_dashboards.py` 生成且 `editable: false` | 没有「看板与告警规则版本一致性」检查：规则改了看板不跟随，观测口径会悄悄漂移，而看板不可手改，问题更难发现 | 文档缺失 | 在 CI 加 `gen_dashboards.py --check`（生成结果与仓库内产物 diff 为空），与 alert rules 同批校验 |

补充（建议级）：判题**逐用例耗时**只落库（`judge_result.time_ms`），没有 per-case 指标 → 「某用例普遍变慢」只能翻库，无法用告警发现。

## 5. 发布与回滚风险（5 项）

| # | 级别 | 文档定位 | 文档现状 | 为什么测不了 / 会漏掉什么 | 判定 | 可测化建议 |
|---|---|---|---|---|---|---|
| E-01 | **高** | [DEPLOYMENT.md#L590](../DEPLOYMENT.md#L590) | JWT「单密钥 HMAC，不支持多密钥灰度」；换密钥后「所有已签发 token 立即失效（含 refresh），全员重登」，且 auth 与 gateway「必须同一窗口一起换，只换一个 = 全站 401」 | 发布可测性最差的一类：回滚只能整体回滚且必然伴随一次全员掉线。没有「灰度换钥」用例，也没有「只换一个服务」的失败演练 | 设计缺失（文档已承认） | 立项 kid 双密钥窗口；验收用例：新旧密钥并存期间两代 token 均可用，撤旧钥后旧 token 401、新 token 正常 |
| E-02 | 低 | [DEPLOYMENT.md#L604](../DEPLOYMENT.md#L604) | `/v1` 别名路由已落地（2026-10-01），三处同步点核对完成 | **回滚时 `/v1` 是否保留没写**：回滚到未启用别名的版本后，已按 `/v1` 接入的客户端会 404，而回滚手册里没有这一步 | 文档缺失 | 在回滚步骤中显式声明「`/v1` 别名保留」；用例：回滚演练后 `GET /v1/problems/page` 仍 200 |
| E-03 | 中 | [ARCHITECTURE.md#L62](../ARCHITECTURE.md#L62) | 网关 → 服务是 `uri: ${GW_*_URI}` **静态单值**直连，扩容/改配置需**重启网关（入口瞬断）** | 发布期间「零中断」无验收项：任何网关重启都必然瞬断，S1 的 99.9% 会被发布吃掉；也没有「双实例网关」方案文档 | 设计缺失 | 定义发布期可用性判据（如网关重启 <5s 且 5xx 不超预算），或补网关双实例 + 前置 LB 方案 |
| E-04 | **高** | [LAUNCH-CHECKLIST.md#L27](../LAUNCH-CHECKLIST.md#L27) | D2 要求「网关与 judge-auth 同批重启，token `type` 校验两侧版本必须一致，否则合法请求被判 401」 | 只是人工契约：版本不一致时**启动阶段无任何自检**，症状是线上 401，而不是启动失败 → 发布顺序错误无法在发布窗口内被发现 | 设计缺失（缺启动期校验） | 启动时校验对端版本（如网关 `/actuator/info` 里带 `token-type-support`），不一致拒绝启动；断言：故意启动旧版 auth，新网关启动失败并给出明确日志 |
| E-05 | 中 | [LAUNCH-CHECKLIST.md#L56](../LAUNCH-CHECKLIST.md#L56) + [DEPLOYMENT.md#L630](../DEPLOYMENT.md#L630) | P5「无 git 远端（回滚点仅本机）」；沙箱镜像按 `name@sha256` 固定 digest，升级流程是「换 digest → 重建 → `verify-p3.py`」 | 回滚能力依赖单机磁盘 + 人工重跑：镜像升级后若判题异常，**没有「回到上一个 digest」的 SOP 与保留策略**，「回滚」在沙箱维度不可执行 | 设计缺失（发布流程缺口） | 规定每个沙箱镜像保留最近 2 个 digest 并写回滚命令；演练：切到旧 digest → `verify-p3.py` 全绿，计时记录 |

补充（建议级）：[LAUNCH-CHECKLIST.md#L26](../LAUNCH-CHECKLIST.md#L26) D1 用 `docker-compose up -d` 全量重建，**没有滚动更新/健康门禁**，发布窗口内的可用性与 S1 无对齐口径。

---

## 6. 结论与优先级

**文档缺失 vs 设计缺失：26 项中 15 项属「文档缺失」（补文档即可测），11 项属「设计缺失」（需补机制）。**
**分级：高 6 项、中 13 项、低 7 项。**

优先修（能直接让现有证据判死、成本最低）：

1. **C-04**（`status=1` 但正文为空，实测 12 条，高）——一条 SQL 就能巡检，且直接影响 AI 点评的用户可见质量。
2. **D-01**（`[req=]` 空值，实测，高）——排障链路断在 worker，事故复盘效率直接受影响。
3. **A-05 / A-03 / A-01**（JWT 算法冲突为高；SSE 字段名、`code:1` 示例为中）——文档与实现/自身冲突，会让所有外部联调踩坑。
4. **B-05**（告警出口为 null，高）——SLO 有定义但无执行力。

优先补机制（成本较高但决定上线风险）：

- **E-01 / E-04**（换钥全站 401、发布顺序无启动期校验，均为高）→ 发布期最容易造成全站 401。
- **B-02**（SSE 心跳/超时未约定，中）→ 长连接在生产网络下必然出问题。
- **C-05 / E-05**（对账 batch 与沙箱镜像回滚 SOP，均为中）→ 决定故障恢复时间能否被承诺。

**本次审查未列入的问题**：纯产品体验、UI 文案、性能数值合理性不在本报告范围（性能见 `docs/PERF.md`）。

## 7. 可直接对外的结论表述（≤2 句）

1. 对 CodeJudge 的 6 份设计文档完成 26 项可测性审查，覆盖接口契约、异常与降级、数据一致性、可观测性、发布回滚 5 类，识别 15 项文档缺失与 11 项设计缺失。
2. 其中 6 项高优先级缺口（JWT 算法冲突、告警出口为空、成功态空正文、requestId 跨 MQ 断链、换钥全站 401、发布顺序无启动校验）均给出文档行号定位与可执行断言建议。
