# 未完成事项提示词集（2026-11-11）

> 来源：2026-11-11 对 `docs/review/E-01-KEY-ROTATION-DRILL.md` 的复核结论。
> 编制依据：`docs/review/TESTABILITY-REVIEW-20261107.md`（6 项高优先级）、`docs/review/TESTABILITY-FIX-PROMPTS.md`（原始要求）。
> 用法：**每个 `##` 段落 = 一次新对话**，整段粘贴即可。一次只做一个，不要合并。

## 清单与顺序

| 序 | 编号 | 要做的事 | 类型 | 是否需要起栈 | 依赖 |
|---|---|---|---|---|---|
| 1 | P1 | 修正 E-01 报告的算法表述，补测试输出与证据声明 | 文档 | 否 | 无 |
| 2 | P2 | C-04 成功态空正文：补执行证据（巡检 0 行 + 降级生效） | 验收 | PG | 无 |
| 3 | P3 | D-01 requestId 跨 MQ：补三服务同 id 命中证据 | 验收 | 全栈 | 无 |
| 4 | P4 | B-05 告警出口：补"真实收到"与"prod 拒启"证据 | 验收 | 监控栈 | 无 |
| 5 | P5 | E-04 契约校验：补反向演练（不一致 → 拒启） | 验收 | auth+gw | 无 |
| 6 | P6 | A-05 三条安全断言：/jwks 404、篡改 401、登录限流 | 验收 | 全栈 | 无 |
| 7 | P7 | 分批提交 + 逐项登记闭环 | 流程 | 否 | P1–P6 全部完成 |

**通用红线**（每段已内嵌，此处便于对照）：不编造输出；跑不通就如实写"未验证/阻塞"并给出阻塞原因；不得为让结果变绿而改断言或改数据；不得声称"已解决"而没有可复制的命令与真实输出。

---

## P1｜修正 E-01 演练报告的算法表述与证据缺口

### 任务

修正 `docs/review/E-01-KEY-ROTATION-DRILL.md`，消除 1 处事实性表述错误、补齐 3 处证据缺口。**只改文档，不改代码、不改测试数字。**

### 背景

2026-11-11 复核结论（已实测，可信）：

1. **硬伤**：报告 §3「演练环境」写「两把 **48 字节**随机钥」，§6 第 4 条写「**48 字节钥 → HS512**……与 A-05 文档口径一致」。
   - 代码 `JwtTool` 走 `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`；jjwt 按**配置字符串的 UTF-8 字节数**选算法：**≥64 字节 → HS512，≥48 → HS384，≥32 → HS256**。
   - 因此「48 字节 → HS512」在算术上不成立，应为 HS384。
   - 唯一自洽解释：48 字节是随机**熵**，base64 编码后为 64 字符 → 64 字节 → HS512。本地 `.env` 的 `CJ_JWT_SECRET` 实测为 **64 字符**，与此吻合。
   - 同时 A-05 定稿文档（`docs/API-REFERENCE.md:53`、`docs/ARCHITECTURE.md:290`）写的是「当前 64 字节 → HS512」，所以"口径一致"这句只有在密钥字符串是 64 字符时才成立。
2. **缺口 A**：§5「单元测试 PASS（auth 66、gateway 28）」只给数字无输出。实测 `mvn -pl judge-auth,judge-gateway -am test` 结果确为 auth 66 / gateway 28、0 失败。
3. **缺口 B**：§5「登录链路回归 PASS（`verify-p1-login.py` 43 项）」无任何输出。
4. **缺口 C**：§4 全部日志为手工摘录，`docs/review/` 下无原始日志文件，无法复核。
5. **缺口 D**：§2 只给「变更文件表」，没有 diff。

### 期望输出

按顺序给出四处修改，每处都给「原文 → 改后」：

1. **算法表述**（§3 密钥行 + §6 第 4 条）：改为「48 字节随机熵经 base64 编码为 64 字符密钥 → 按 `Keys.hmacShaKeyFor` 阈值（≥64 字节）选 HS512」，并保留「header 实测 `"alg":"HS512"`」这一观测事实。§6 第 4 条的「与 A-05 文档口径一致」改为引用具体出处（`API-REFERENCE.md:53` 的「64 字节 → HS512」）。
2. **补 mvn 输出**：在 §5 单测行下方插入代码块，只保留两个 `Tests run:` 汇总行与 reactor 结论行，标注执行命令与时间。
3. **43 项回归**：能复跑则贴输出；不能复跑则把 §5 该行改为「脚本存在、本轮未留存输出」，并在 §4 或 §6 注明未复跑原因。**不允许含糊写成 PASS。**
4. **原始日志**：二选一——(a) 把 §4 各段日志原文落盘到 `docs/review/raw-e01-20261111/`（按步骤分文件）并在 §4 标注文件路径；(b) 在文首「结论」下加一句声明「本文日志为演练期手工摘录，未保留原始日志文件」。
5. **diff 摘要**：在 §2 表后补一段 `git diff --stat` 范围的说明（涉及 auth/gateway 各几个文件、增删行数），不要求贴完整 diff。

### 约束条件

- **不得反向迎合**：如果核对后怀疑密钥实际是 48 字节（即 header 不可能为 HS512），**停下来在回答开头报告冲突**，不要改数字去凑。
- 不得修改 §5 的 PASS/实测数字，除非有新的实跑输出作为依据。
- 不得删除 §6「踩坑」中任何一条（含 refresh 失败仍 HTTP 200、refresh 不轮换、索引从 0 连续、本地 `.env` 已换钥）。
- 修改后保持文件其余章节结构与锚点不变（§8 相关文件链接不得失效）。

### 完成判据

四处修改全部落到文件里，且全文不再出现「48 字节钥 → HS512」这种无法自洽的因果说法；`grep -n "48 字节" 该文件` 结果每条都能被代码阈值解释。

---

## P2｜C-04 成功态空正文：补执行证据

### 任务

为 C-04 的整改补上**执行证据**，产出一份可复核的验证记录。代码已改完，缺的是"跑过并留下输出"。

### 背景

- 整改内容（已落地）：`judge-ai/src/main/resources/db/migration/V2__ai_review_done_requires_content.sql`（先订正存量 → 再加 `ck_ai_review_done_has_content` CHECK 约束）；应用侧 `judge-ai/src/main/java/com/codejudge/ai/repository/AiReviewRepository.java#updateContent` 在 `status==STATUS_DONE && content.isBlank()` 时降级为 `status=2` + `error_msg=EMPTY_CONTENT`。
- 库：PostgreSQL，连接信息见 `.env`：`POSTGRES_URL=jdbc:postgresql://localhost:5433/judge_ai`、`POSTGRES_USERNAME=postgres`。
- 缺口：无「订正后巡检返回 0 行」的输出；无 `AiReviewRepositoryTest` 的运行输出；未确认 V2 迁移是否真的执行过。
- 原始缺口数据（2026-11-07 15:30）：`status=1` 且正文为空 **33 条**（历史 12 + 补齐语料 21）。

### 期望输出

1. **迁移执行确认**：查询 `flyway_schema_history` 中 `V2` 记录（version / description / success / installed_on），贴结果。若未执行，说明为什么（服务未启动？手动执行过？）。
2. **订正前后对照**（只读）：
   ```sql
   -- 订正前/后各跑一次，记录行数与 id 列表
   SELECT count(*) AS empty_done FROM ai_review
   WHERE status = 1 AND (content IS NULL OR length(btrim(content)) = 0);
   ```
3. **订正后巡检 = 0 行**：贴出返回 `0` 的真实输出。
4. **约束存在性**：`\d ai_review` 或查 `pg_constraint` 确认 `ck_ai_review_done_has_content` 已存在，贴输出。
5. **降级路径验证**：二选一——(a) 跑 `mvn -pl judge-ai test -Dtest=AiReviewRepositoryTest` 并贴输出；(b) 若测试未覆盖"成功态空正文降级"，补一条用例（`updateContent(id, "", ..., STATUS_DONE, null)` → 断言落库 status=2 且 error_msg=EMPTY_CONTENT）后贴输出。
6. **证据落盘**：新建 `docs/review/C-04-VERIFY.md`，包含上述 5 项的命令、输出、时间与执行人，末尾一行结论（"存量已清零 / CHECK 生效 / 降级路径已验证"）。

### 约束条件

- **只读查询优先**；任何写操作前先给 `SELECT` 结果与影响行数。
- **不得为了让巡检变成 0 行而删行**（只允许 V2 迁移里那种 `status=1→2` 的订正）。
- 不得关闭或删除 CHECK 约束来"让服务起来"；若约束导致启动失败，报告冲突而不是绕过。
- 不得声称"已清零"除非贴出真实 `count = 0`。
- 改动限定 `judge-ai` 与 `docs/review/`。

### 完成判据

`docs/review/C-04-VERIFY.md` 里能一眼看到：V2 已执行、空正文计数为 0、约束存在、降级路径有输出。

---

## P3｜D-01 requestId 跨 MQ：补三服务同 id 命中证据

### 任务

为 D-01 的整改补上**端到端证据**：一次提交的 requestId 能在 gateway / submission / worker 三处日志中被检索到。代码已改完，缺的是运行输出。

### 背景

- 整改内容（已落地）：`judge-common/src/main/java/com/codejudge/common/logging/RequestIdContext.java`（生成带前缀 id + MDC 设置/恢复；前缀 `cmp-` 补偿重投、`cron-` 定时对账、`hb-` 心跳、`mq-` 消费兜底）；`RocketMQTemplate` 投递时 `msg.putUserProperty("requestId", id)`；`RocketMQConsumerContainer#handleOne` 从 `msg.getUserProperty("requestId")` 回填 MDC，缺失则生成 `mq-` 前缀 + WARN，`finally` 里 `RequestIdContext.restore(previous)`。
- 日志格式：`JsonLogLayout` 输出 JSON，字段名 `requestId`（Loki 查询依赖，不可改）。
- 原始缺口：worker 消费侧日志全部为 `[req=]` 空值。

### 期望输出

1. **正向链路**：起栈后提交一次判题，取该次 HTTP 响应头里的 `requestId`，分别在三处日志中 `grep` 同 id。
   - 命令形如：`docker compose --profile app logs judge-gateway | grep <requestId>`（三个服务各一条），或按 JsonLogLayout 的 JSON 字段过滤。
   - 期望：**≥3 条命中**，且 worker 侧该行 `requestId` 非空。贴真实输出（可截取关键行）。
2. **旧消息兼容**：构造/找一条不带 `requestId` 用户属性的消息（例如手动向判题 topic 发一条最小消息），确认消费端行为是「WARN + 生成 `mq-` 前缀 + 消息被正常处理」，不抛异常、不丢消息。贴 WARN 日志原文与消息处理结果。
3. **MDC 不串号**：连续两条不同 requestId 的消息，确认第二条日志里没有继承第一条的 id（说明 `finally` 清理生效）。贴两条相邻日志。
4. **边界说明**：说明 requestId 现在覆盖到哪、仍然断在哪里（如沙箱内进程），不要声称"全链路打通"。
5. **证据落盘**：新建 `docs/review/D-01-VERIFY.md`，含命令、输出、结论。

### 约束条件

- 不得修改 JSON 日志字段名 `requestId`。
- 不得通过"在 worker 里再生成一个新 id"冒充跨链路传递——必须证明**同一个 id** 从 HTTP 头一路传到 worker。
- 若某跳确实拿不到同 id（例如 handler 内部另开线程），**如实报告断点位置**，不要用相近 id 敷衍。
- 不得声称"全链路可串"，只写实测覆盖到的范围。

### 完成判据

能贴出同一次提交在三个服务的日志行，且 `requestId` 完全一致；旧消息兼容有 WARN 证据。

---

## P4｜B-05 告警出口：补"真实收到"与"prod 拒启"证据

### 任务

为 B-05 的整改补上**两段运行证据**：告警真的送达接收端；生产环境下出口为空时真的拒绝启动。代码与配置已改，缺运行输出。

### 背景

- 整改内容（已落地）：`deploy/monitoring/alertmanager/entrypoint.sh`（`ALERTMANAGER_ENV=prod` 且 receiver 为空或 `null` → `exit 1`；其它环境 WARN 后继续）、`deploy/monitoring/alertmanager/templates/`（邮件模板）、`deploy/monitoring/alertmanager/webhook-sink.py`（本地接收端）、`alertmanager.yml.tpl` 与 `docker-compose.monitoring.yml` 相应改动。
- 原始缺口：默认 `receiver: 'null'`，告警只出现在 Alertmanager UI，SLO 违规不产生任何通知；`LAUNCH-CHECKLIST` A2、`DEPLOYMENT.md:505` 均已记录该事实。
- 环境：Docker Compose，本机单机，无外网邮件中继。

### 期望输出

1. **真实送达**：
   - 说明本次用哪条通道（`webhook-sink.py` 或企业微信机器人），为什么在无外网邮件中继时选它；
   - 触发一条测试告警的命令（`amtool alert add` 或向 Alertmanager API POST）；
   - 接收端实际收到的原始报文（**脱敏**：去掉 webhook key / token / 完整 URL）；
   - Alertmanager `GET /-/alerts` 的返回。
2. **prod 拒启**：`ALERTMANAGER_ENV=prod` + `ALERTMANAGER_DEFAULT_RECEIVER=null` 启动 → 容器退出码非 0，贴 `entrypoint.sh` 的输出行与 `docker inspect` 的退出码。
3. **dev 行为**：同配置去掉 `ALERTMANAGER_ENV=prod` → 能起来且打出 WARN，贴 WARN 行。
4. **preflight F1 判据**：把"触发 → 期望收到 → 超时判失败"写成 `LAUNCH-CHECKLIST` 可勾选的一行，追加到该文件对应条目下。
5. **证据落盘**：新建 `docs/review/B-05-VERIFY.md`。

### 约束条件

- **不得把真实 webhook / token / 邮箱口令写进仓库**，只能从 `.env` 读取；贴输出必须脱敏。
- 不得把 prod 的"拒启"降级成"仅告警"——那等于回到原缺口。
- 不得用"UI 里能看到告警"冒充"收到了通知"：UI 是 Alertmanager 自身页面，不是出口。
- 若当前环境确实无法配置任何真实出口，**如实报告阻塞**（缺什么：外网、企业微信 key、SMTP 账号），并至少完成 prod 拒启 + dev WARN 两项。

### 完成判据

有非空接收端的原始报文（脱敏）+ prod 退出码非 0 + dev WARN，三项齐全；否则明确标注阻塞项。

---

## P5｜E-04 契约校验：补反向演练

### 任务

E-04 目前只验证了"通过"路径（E-01 演练日志里出现过 `[contract-check] 通过：本地=v1 对端=v1`）。本任务补**失败路径**：契约版本不一致时，网关必须拒绝启动。

### 背景

- 实现：`judge-gateway/src/main/java/com/codejudge/gateway/contract/TokenContractCheckRunner.java`（读 auth `/actuator/info` 的 `token-claim-contract`，与本地 `cj.security.contract-check.expected` 比对，不一致 `[contract-check] 拒绝启动`）；配置类 `judge-gateway/src/main/java/com/codejudge/gateway/config/TokenContractProperties.java`。
- 关键配置项（prefix `cj.security.contract-check`）：
  - `enabled`（默认 true，本地可关）
  - `expected`（默认 `v1`）
  - `failOnUnreachable`（默认 true；**版本不一致不受此开关影响，永远拒启**）
  - `authBaseUrl`、`maxAttempts`、`retryDelayMs`、`requestTimeoutMs`
- auth 侧声明 `app.token-claim-contract`，环境变量 `CJ_TOKEN_CONTRACT_VERSION`。
- 原始缺口：`LAUNCH-CHECKLIST` D2 只是人工契约，发布顺序错了症状是线上 401 而非启动失败。

### 期望输出

1. **不一致 → 拒启**：把网关 `expected` 改成 `v2`（或 auth 的 `CJ_TOKEN_CONTRACT_VERSION` 改成 `v2`），启动网关 → 进程退出、日志含拒启原因（本地版本、对端版本、对端地址）。贴退出码 + 日志原文。
2. **反方向再验一次**：交换哪一侧改，确认结论对称。
3. **对端不可达的两种行为**：
   - `failOnUnreachable=true` + auth 未启动 → 拒启（贴日志）；
   - `failOnUnreachable=false` + auth 未启动 → WARN 放行（贴日志）。
   并在报告里强调：**不一致不走这个开关**。
4. **开关关闭**：`enabled=false` → 跳过校验且日志明确（`[contract-check] 跳过`），贴输出。
5. **回滚说明**：给出"演练期间如何临时关闭校验"的一行操作（改哪个环境变量、重启哪个服务），并注明生产默认值。
6. **证据落盘**：新建 `docs/review/E-04-VERIFY.md`，含上述 4 组命令与输出。

### 约束条件

- **不得为了让网关起来而把"不一致"改成放行**；若发现代码在版本不一致时仍能启动，这是缺陷，**报告而不绕过**。
- 不得修改 token 的 claim 结构（`type` 键名与 `access`/`refresh` 取值）。
- 演练后必须把配置恢复原值，并在报告里写一句"已恢复"。
- 不得声称演练完成，除非两次反向演练都有退出码与日志。

### 完成判据

`E-04-VERIFY.md` 里能同时看到：不一致拒启（双向）、不可达两种行为、开关关闭跳过，四组都有真实输出。

---

## P6｜A-05 三条安全断言：实际执行并留证

### 任务

A-05 的文档已统一为「HMAC 对称、64 字节密钥 → HS512、`/jwks` 已删除」。本任务执行该条目要求的三条可执行断言，并留证。**只跑测试，不改代码。**

### 背景

- 定稿口径：`docs/API-REFERENCE.md:53`（算法与阈值）、`docs/ARCHITECTURE.md:290`（算法强度复核方式）、`docs/ARCHITECTURE.md:342`（篡改签名的注意事项）。
- 已知事实：`/jwks` 端点已删除（`JwtTool.java`、`AuthClient.java`、`JwtProperties.java` 三处注释可证）。
- 三条断言（来自 A-05 提示词）：
  1. `GET /jwks` → **404**
  2. 篡改签名的 token → **401**
  3. 登录限流覆盖 `/accounts/login`（连续失败登录 → **429**）

### 期望输出

1. **断言①**：`curl -s -o /dev/null -w "%{http_code}" $GW/jwks` → 404。同时补一条对照：`$GW/accounts/login` 可访问（证明 404 不是网关整体不可用）。
2. **断言②**：拿一枚有效 access token，**改签名的中间部分**（不要改末位——`ARCHITECTURE.md:342` 已说明 HS512 签名 base64url 末位含填充位，改末位可能仍是合法编码）后请求需鉴权接口 → 401。贴命令与状态码，并说明改的是第几段、第几个字符。
3. **断言③**：对 `/accounts/login` 连续提交错误密码，记录第一次 401 与第一次 429 的**第几次**，贴命令与关键输出；确认限流键包含用户名或 IP（按 `LoginRateLimitKeyResolver` 的实际实现说明）。
4. **限流恢复**：说明冷却窗口多长（配置项与值），以及窗口过后可正常登录（贴一条 200）。
5. **证据落盘**：把三条断言追加到 `docs/review/TESTABILITY-REVIEW-20261107.md` 的 A-05 行下方作为「验证记录」，或单独建 `docs/review/A-05-VERIFY.md` 并在原报告 A-05 行加一行指向链接。

### 约束条件

- **只做只读/阴性测试**，不得修改账号密码、不得删除用户、不得放宽限流阈值来"验通"。
- 不得把真实用户密码写进仓库或报告；用测试账号并脱敏。
- 断言②若得到 200，说明签名校验有缺陷，**立即报告**，不要换个请求方式掩盖。
- 限流断言若达不到 429，如实记录"未触发"，并说明是阈值过高还是键设计问题。

### 完成判据

三条断言均有真实状态码；其中断言③给出"第几次触发 429"的具体数字。

---

## P7｜收口：分批提交 + 逐项登记闭环

### 任务

把工作区改动**按缺口分批提交**，然后按 `docs/review/TESTABILITY-REVIEW-20261107.md` 顶部声明的要求，**逐项登记**已处置条目。**这是唯一能让"已解决"站得住的一步。**

### 背景

- 当前状态：`git status` 显示 24 项修改 + 多个未跟踪目录，**全部未提交**（最新 commit 仍是更早的文档改动）。
- 该报告第 9 行的「闭环状态声明」明确要求：引用本报告时不得声称已解决；若某项确已处置，必须有**提交号 + 对应断言/命令的执行结果**，并单独登记；不得批量改声明。
- 已完成且有证据的：E-01（有演练报告）、P1–P6 产出的验证记录（本轮补齐后）。
- 未验证的不许登记。

### 期望输出

1. **提交计划表**：先给一张表，列出每个提交的 message、包含的文件、对应缺口编号。建议分组（按依赖与可回滚性）：
   - `test(C-04): status=1 必带非空正文 + V2 迁移` —— `judge-ai/**`、`docs/review/C-04-VERIFY.md`
   - `feat(D-01): requestId 跨 MQ 透传与消费端回填` —— `judge-common/**`、`submission/worker` 相关、`D-01-VERIFY.md`
   - `feat(B-05): 告警出口强制 + prod 拒启` —— `deploy/monitoring/**`、`docs/review/B-05-VERIFY.md`
   - `feat(E-04): token claim 契约启动期校验` —— `judge-gateway/**`（contract 包、TokenContractProperties）、`E-04-VERIFY.md`
   - `feat(E-01): JWT kid 多钥灰度换钥` —— `judge-auth/**`、`judge-gateway` 密钥相关、`E-01-KEY-ROTATION-DRILL.md`
   - `docs(A-05): JWT 算法口径统一` —— `docs/API-REFERENCE.md`、`docs/ARCHITECTURE.md`、`A-05-VERIFY.md`
   - 最后单独一笔：审查与提示词文档（`docs/review/**` 其余）
   **执行前先确认这份分法，再动手。**
2. **逐笔提交**：每笔一条 `git commit`，message 带缺口编号；提交后列出 `git log --oneline -7` 的 hash。
3. **登记闭环**：在第 9 行声明**下方**新增一节「已处置登记（逐项）」，表头：`编号 | 提交号 | 验证方式（命令/断言） | 结果 | 登记日期`。只登记**有 P1–P6 证据**的条目。
4. **未处置清单**：同一节里明确列出仍为"待处置"的条目（中/低优先级 19 项），一句话说明"未验证，不登记"。
5. **一致性检查**：全库 `grep -rn "已解决\|已关闭\|已实施"`，确认没有超出登记范围的表述；有则列出并改正。

### 约束条件

- **不得 `--amend`、不得 `push --force`、不得跳过 hook**。
- 不得把多个缺口塞进一个提交（登记需要可归属的提交号）。
- 不得登记没有执行结果的条目；宁可少登记，不可虚登记。
- 提交前确认 `.env` 未被纳入版本控制（含真实密钥）；若发现 `.env` 被跟踪，**停下来先报告**。
- 不得删除或改写原报告中的历史判定文字（只需追加登记节）。

### 完成判据

`git log` 能看到按缺口分开的提交；报告第 9 行下方有登记表，每行都有可点开的提交号与对应验证记录文件。

---

## 附：全部完成后的连带影响

1. **对外口径可升级**：当前表述是「识别 6 项高优先级缺口并给出可执行断言建议」。只有在 P7 登记了 N 项之后，才能改为「完成 N 项闭环」，且 **N 必须等于登记表行数**。这一步单独做，不要顺手改。
2. **`TESTABILITY-FIX-PROMPTS.md` 需加一句状态**：完成后在该文件顶部注明"6 项已执行，验证入口见 `docs/review/*-VERIFY.md`"，避免下次误以为仍未开工。
3. **A-05 与 E-01 的算法口径必须始终一致**：任何一侧写 HS256 或"48 字节 → HS512"都是同一类矛盾复发，改完顺手 `grep -rn "HS256" docs/` 核一遍。
