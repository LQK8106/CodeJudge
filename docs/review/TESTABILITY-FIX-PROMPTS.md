# 可测性缺口修复提示词（6 项高优先级）

> 来源：`docs/review/TESTABILITY-REVIEW-20261107.md`（2026-11-07 只读审查，26 项发现 / 高 6 项）。
> 用途：每个缺口一段**自包含提示词**，在新对话中逐条粘贴执行。**一次只解决一个缺口**，不要合并。
> 生成日期：2026-11-10
> **状态（2026-11-11 补记）：本文件的 6 项高优先级缺口已全部处置完毕**，逐项提交号与验证记录见
> [TESTABILITY-REVIEW-20261107.md](./TESTABILITY-REVIEW-20261107.md) 的「✅ 已处置登记（2026-11-11）」；
> 文件本身仅作历史要求保留，不再作为待办依据。

## 使用说明

1. **一次一个**。6 个缺口的改动面互不重叠（judge-ai / judge-worker / 文档 / 部署 / judge-gateway / 双端配置），合并执行会让验证证据无法归属。
2. **按成本从低到高**执行，先拿能立刻验证的信任度：

| 顺序 | 编号 | 缺口 | 类型 | 主要落点 | 成本 |
|---|---|---|---|---|---|
| 1 | C-04 | 成功态空正文 | 设计缺失（缺成功态校验） | judge-ai | 低（1 条 SQL + 1 处守卫） |
| 2 | D-01 | requestId 跨 MQ 断链 | 设计缺失 | judge-common / worker | 低 |
| 3 | A-05 | JWT 算法两份文档冲突 | 文档缺失 | 文档 | 低 |
| 4 | B-05 | 告警出口为 null | 设计缺失 | deploy/monitoring | 中 |
| 5 | E-04 | 发布顺序无启动期校验 | 设计缺失 | gateway + auth | 中 |
| 6 | E-01 | 换钥全站 401 | 设计缺失 | gateway + auth | 高 |

3. **通用前提**（每段提示词已内嵌，此处列出便于对照）：仓库 `D:\1\CodeJudge`，Java 21 / Spring Boot 3.3.5 / Spring Cloud Alibaba 微服务，PostgreSQL（judge-ai）+ MySQL（业务库）+ Redis + RocketMQ，Docker Compose 部署在 `deploy/`。
4. **通用红线**（已写入每段的约束条件）：不得声称"已修复"而没有执行输出；不得为了让测试变绿而弱化断言；不得删历史数据；改动后必须给出一条可复现的验证命令及其真实输出。

---

## 1｜C-04　成功态空正文（高 · 设计缺失）

### 缺口描述

`ai_review` 表中 `status=1` 表示"生成成功"，但实测存在 **`status=1` 且 `length(trim(content))=0`** 的记录：

- 历史 12 条：id 45 / 50 / 53 / 57 / 63 / 64 / 71 / 76 / 78 / 81 / 89 / 91
- 补齐语料期间新增 21 条
- **累计 33 条**（2026-11-07 15:30 查询）

即"成功"与"有正文"是两件事，却没有任何代码或约束保证 `status=1 ⇒ 正文非空`。上游把空正文判成成功，下游评测与前端展示会看到"生成完成但正文为空"。这是能被一条 SQL 直接判死的契约缺口，且已在规模化复现（32 条请求中 13 条写入空正文、4 条 HTTP 500 超时，重试又新增空正文记录）。

### 所需背景

- 落库点：`judge-ai/src/main/java/com/codejudge/ai/repository/AiReviewRepository.java`
  - `insert(...)` 写 `status=0`（生成中）
  - `updateContent(id, content, tokensIn, tokensOut, status, errorMsg)` 直接 `UPDATE ai_review SET content=?, ..., status=?`，**无任何入参校验**——空正文 + `status=1` 由这一句写入
- 调用方：先 `grep -rn "updateContent(" judge-ai/src/main` 定位所有置 `status=1` 的分支（含正常完成、降级、异常兜底三条路径）
- 库：PostgreSQL，库名 `judge_ai`；表 `ai_review`（含 `status` / `error_msg` / `model` / `content`）
- 既有实测线索：库中存在 3 条 `model='degraded'` 的 171 字模板正文，另有 12 条 `status=1` 但长度为 0 的记录

### 期望输出

1. **调用点清单**：列出所有能把 `status` 置为 1 的代码位置（文件:行号 + 触发条件），标明哪几条可能写入空正文。
2. **成功态守卫**（代码）：在落库前强制 `status=1 ⇒ trim(content) 非空`，否则降级为失败态并写 `error_msg`。给出 diff。
3. **巡检 SQL**（可重复执行，只读）：
   ```sql
   SELECT id, submission_id, model, status, length(trim(content)) AS len, create_time
   FROM ai_review
   WHERE status = 1 AND length(trim(content)) = 0
   ORDER BY id;
   ```
4. **数据订正脚本**：把 33 条存量记录改为失败态并写 `error_msg='EMPTY_CONTENT'`。**必须先给 dry-run 输出（命中行数 + id 列表），再给执行语句**。不得删除任何行。
5. **可选的 DB 约束**：评估是否加 `CHECK (status <> 1 OR length(trim(content)) > 0)`，并说明存量数据必须先订正才能加（给出顺序与失败回退）。
6. **回归测试**：至少 1 条用例覆盖"生成返回空正文 → 落库为失败态且 error_msg 非空"，给出测试类路径与 `mvn -q -pl judge-ai test` 的输出。

### 约束条件

- 不得把空正文当成功落库；不得用"前端不展示就行"作为方案。
- 不得删除、不得 `TRUNCATE` 历史记录，只允许改 `status` / `error_msg` / `update_time`。
- 加 DB 约束前必须先给出存量订正结果；顺序错了会导致启动即失败。
- 改动范围限定 `judge-ai`；若必须动表结构，单独列出 DDL 并说明回退语句。
- 不得声称"已修复"，除非附上巡检 SQL 在修复后的真实返回（期望 0 行）。

---

## 2｜D-01　requestId 跨 MQ 断链（高 · 可观测性设计缺失）

### 缺口描述

架构文档称"`requestId` 透传：网关生成，全链路日志可串"，但**实测不成立**：`judge-worker` 消费侧日志全部是 `[req=]` 空值。requestId 没有跨 MQ 传递，"全链路可串"只到 MQ 投递为止，worker 之后的链路（判题引擎、沙箱、结果写回）全部断链。事故复盘时无法用一次提交的 requestId 串起完整链路。

### 所需背景

- 写入端：`judge-common/src/main/java/com/codejudge/common/interceptor/RequestIdInterceptor.java`
  - 从请求头读 `requestId`，缺失则生成 UUID（去横线），`MDC.put`，响应头回写，`afterCompletion` 里 `MDC.remove`
  - 键名常量：`judge-common/src/main/java/com/codejudge/common/constants/Constant.java` 的 `REQUEST_ID_HEADER = "requestId"`
- 日志格式：`judge-common/src/main/java/com/codejudge/common/logging/JsonLogLayout.java`
  - 从 MDC 取 `requestId` 序列化进 JSON
  - 类注释已自认："无请求上下文的后台线程（MQ 消费、心跳）该字段缺失"
  - 输出字段名 `requestId` 被 Loki 查询表达式依赖：`{application="judge-submission"} | json | requestId="..."`
- 投递端：`judge-submission/src/main/java/com/codejudge/submission/mq/JudgeEventPublisher.java`、`judge-common/src/main/java/com/codejudge/common/mq/RocketMQTemplate.java`
- 消费端：`judge-worker`（`worker/engine/JudgeEngine.java` 及其 consumer 入口）
- 补偿重投：`judge-submission/src/main/java/com/codejudge/submission/service/JudgeCompensationService.java` 也会发同一条消息——**这条路径没有 HTTP 上下文，requestId 天然为空**，必须单独定义

### 期望输出

1. **方案对比并选一个**：
   - A. 写进 RocketMQ 消息 header（`Message.putUserProperty` / 自定义 header）
   - B. 写进消息体字段（`JudgeEvent.requestId`）
   对比维度：旧消息兼容性、可见性（MQ 控制台能否直接看到）、是否需改 DTO、是否影响序列化体积。给出选择理由。
2. **实现 diff**：生产端写入 + 消费端 `MDC.put` / `finally { MDC.remove }`。
3. **无上下文路径的处理**：补偿重投、定时对账、心跳线程各自生成带前缀的新 id（如 `cmp-` / `cron-`），说明理由，不得留空。
4. **验收证据**：
   - 一条命令从日志里按 requestId 命中 gateway / submission / worker 三处
   - 期望：同一 requestId ≥3 条，且 worker 侧 `[req=]` 不再为空
   - 贴上真实输出
5. **链路示意**：一段文字说明 requestId 现在能覆盖到哪里、仍然断在哪里（如沙箱内部进程不可控）。

### 约束条件

- **旧消息（无 requestId）不得抛异常**：缺失时生成新 id 并记一条 WARN，不得 NPE、不得丢弃消息。
- **不得改 JSON 日志字段名** `requestId`——Loki 查询与看板依赖它。
- MDC 必须在 `finally` 清理，防止线程池复用导致不同任务串号（这是同类改造最常见的生产事故）。
- 不得把 requestId 塞进用户可见的 HTTP 响应体之外的敏感位置（响应头已有，不重复加）。
- 不得声称"全链路已打通"——只写实际验证过的范围。
- 改动优先落在 `judge-common`（一处改、多服务受益），避免在 worker 里重复实现。

---

## 3｜A-05　JWT 算法两份文档冲突（高 · 文档缺失）

### 缺口描述

同一系统的两份权威文档对令牌算法给了**互斥**描述：

| 文档 | 表述 |
|---|---|
| `docs/API-REFERENCE.md#L53` | 令牌算法 **HS256（HMAC 对称）**；`/jwks` 已随 2026-09-25 加固删除 |
| `docs/ARCHITECTURE.md#L14` | 架构图标注 **RS256 + JWKS** |
| `docs/ARCHITECTURE.md#L109` | 登录时序写 **RS256 签发** |

后果：验签算法决定安全用例形态（HS256 需共享密钥、RS256 需公钥轮换）。两份文档矛盾时，除"令牌被篡改必须 401"之外的任何断言都写不出来，密钥轮换演练（E-01）也无法立项。

**代码事实（已核实）**：`JwtTool` 用 `Jwts.builder()....signWith(key)`，`key` 来自 `@Value("${cj.jwt.secret}")`，即环境变量 `CJ_JWT_SECRET` 的对称密钥 → **实际是 HMAC（HS256），不是 RS256**。`/jwks` 端点确已删除，三处注释均有记录：`judge-auth/.../JwtTool.java:87`、`judge-api/.../client/auth/AuthClient.java:9`、`judge-gateway/.../config/JwtProperties.java:27`。

### 所需背景

- 待改文档：`docs/API-REFERENCE.md`（L53 附近）、`docs/ARCHITECTURE.md`（L14 架构图、L109 时序）
- 复核代码：`judge-auth/src/main/java/com/codejudge/auth/common/util/JwtTool.java`（`createToken` 的 `signWith(key)`、`parse` 的 `verifyWith(key)`）
- 密钥来源：`judge-auth/src/main/resources/application.yml:101`、`judge-gateway/src/main/resources/application.yml:192`，均 `${CJ_JWT_SECRET}`
- 约束来源：这两份文档被 `docs/` 内其他文件按行号引用（如 `#L14`、`#L109`），改行数会打断引用链

### 期望输出

1. **一段定稿文本**（可直接替换的中文段落），统一描述：对称 HMAC（HS256）、`CJ_JWT_SECRET` 双端一致注入、`/jwks` 已删除及其原因（HMAC 无公钥可分发，暴露即等于交出伪造能力）。
2. **逐条修改清单**：文件 + 行号 + 原文 + 改后（表格形式），覆盖 ARCHITECTURE 两处；API-REFERENCE 复核后若无误则标注"无需改"。
3. **引用链影响说明**：本次改动是否使 `#L14` / `#L109` 之外的其他行号引用失效；若会，列出需同步的文件。
4. **三条可执行断言**（附命令与期望结果）：
   - `GET /jwks` → **404**
   - 篡改签名的 token → **401**
   - 登录限流覆盖 `/accounts/login`（说明如何验证：连续 N 次失败登录 → 429）
5. **一页说明**："为什么本系统不用 RS256"（对称单密钥的取舍、已知代价见 E-01），供评审引用。

### 约束条件

- **只改文档，不改代码**。若核对代码时发现实现并非 HS256，**立刻停下并在回答开头报告**，不要顺手改文档去迎合代码。
- 不得引入 RS256 / JWKS / 公钥轮换的任何表述（除"已删除、不再使用"的历史说明）。
- 尽量保持原行号稳定：优先做**等行替换**，避免打断其他文档的 `#L` 锚点；若必须增删行，在第 3 项里列出受影响引用。
- 不得声称已跑安全测试，除非真的执行了第 4 项的三条断言并贴出结果。
- 不得修改 `docs/review/` 下的历史审查报告（它是时点证据）。

---

## 4｜B-05　告警出口为 null（高 · 设计缺失）

### 缺口描述

Alertmanager 路由的接收器由环境变量决定，**默认解析为 `null`**：告警规则语法正确、阈值也校准过，但出口是断的——**任何 SLO 违规在真实故障时都不会产生通知**。当前"告警有效性"没有任何验收判据，SLO 有定义、无执行力。

### 所需背景

- 配置模板：`deploy/monitoring/alertmanager/alertmanager.yml.tpl`
  - L13–18 注释：默认走 `null` receiver，告警只出现在 Alertmanager UI；可用环境变量 `ALERTMANAGER_DEFAULT_RECEIVER`（null / email / webhook / wecom）、`ALERTMANAGER_CRITICAL_RECEIVER`（注释写明"生产不可为 null"）
  - L60 默认路由 `receiver: '${ALERTMANAGER_DEFAULT_RECEIVER}'`；L69 critical 路由；L76 兜底路由
  - L92–94 `receivers:` 下定义了 `name: 'null'`
  - L59 注释："compose 保证它至少有值 'null'"
- 记录该事实的文档：`docs/LAUNCH-CHECKLIST.md#L14`（A2 项）、`docs/DEPLOYMENT.md#L505`
- 告警规则与看板：`deploy/monitoring/`（Prometheus 规则、Grafana 看板由 `gen_dashboards.py` 生成）
- 运行形态：Docker Compose，本机单机环境

### 期望输出

1. **通道选型**：在 webhook / wecom / email 中选一个**当前环境真实可达**的，说明为什么（本机无企业邮箱中继时 email 不可达，优先 webhook 或企业微信机器人）。给出理由后再动手。
2. **配置 diff**：`alertmanager.yml.tpl` 的必要改动 + `.env.example` 新增变量（**真实 webhook 地址只能从环境变量注入，绝不写进仓库**）。
3. **启动期自检**：当 receiver 解析为 `null` 时——
   - `dev` 环境：启动成功但打 **WARN**，日志明确"告警无出口"
   - `prod` 环境：**拒绝启动**或打 **ERROR + 非零退出**
   给出实现位置（模板渲染处或 compose entrypoint）与 diff。
4. **preflight 判据 F1**：一条能真实触发测试告警并确认"收到"的步骤，写成 `LAUNCH-CHECKLIST` 可勾选的形式（触发命令 → 期望在接收端看到 → 超时判失败）。
5. **周期演练方案**：每周一次静默告警（`amtool alert add` 或 Prometheus 侧注入），失败即记一条 issue。
6. **验收证据**：贴出触发命令、Alertmanager 侧 `/-/alerts` 的输出、接收端收到的记录（文字描述即可，**不要贴出含 token 的 URL**）。

### 约束条件

- **不得把真实 webhook / token / 邮箱密码写入仓库**，只能进 `.env` 与 `.env.example` 占位。
- **不得破坏 dev 现状**：dev 允许 `null`，但必须是**显式选择 + WARN**，不能是"默认就是空"这种静默状态。
- 不得为了让服务起来而把 prod 的校验降级为仅告警；prod 缺出口应当拒绝启动。
- 不得声称"告警已验证"，除非附上接收端确实收到的证据。
- 改动限定 `deploy/monitoring/` 与 compose/env 文件，不碰业务代码。

---

## 5｜E-04　发布顺序无启动期校验（高 · 设计缺失）

### 缺口描述

`LAUNCH-CHECKLIST` D2 要求"网关与 judge-auth 同批重启"，原因是 token `type` 校验两侧版本必须一致，否则**合法请求被判 401**。但这只是**人工契约**：版本不一致时启动阶段没有任何自检，症状是线上 401，而不是启动失败——发布顺序错误无法在发布窗口内被发现，只会被用户先发现。

### 所需背景

- 消费侧（校验方）：`judge-gateway/src/main/java/com/codejudge/gateway/util/JwtUtils.java`
  - L46 注释：token 类型校验，只有 access token 可作为接口凭证
  - L48：`if (!"access".equals(claims.get("type")))` → 不满足即判 401
  - 同链路：`judge-gateway/src/main/java/com/codejudge/gateway/filter/AuthGlobalFilter.java`
- 生产侧（签发方）：`judge-auth/src/main/java/com/codejudge/auth/common/util/JwtTool.java`
  - `createToken(...)` 写 `.claim("type", access ? "access" : "refresh")`
  - `parseTokenType(String)` 供服务内部区分类型
- 契约文档：`docs/LAUNCH-CHECKLIST.md#L27`（D2）
- 关键风险：`type` 的**键名**或**取值**任一变化（例如改成枚举、加前缀、拆分 access/refresh 为两个 claim），两侧不同步就是全站 401
- 已有暴露端点：两侧均有 Actuator（可读 `/actuator/info`、`/actuator/health`）；注意网关有 `ActuatorGuardFilter`，需确认 info 是否被拦

### 期望输出

1. **契约版本化设计**：定义一个显式版本标识（如 `token-claim-contract=v1`），说明它锚定什么（claim 键名 + 允许取值 + 是否必填），以及什么情况下必须递增。
2. **暴露方式**：两侧 `/actuator/info` 增加该字段的实现（配置项 + 构建注入），确认 `ActuatorGuardFilter` 不拦截该路径或给出放行规则。
3. **启动期校验实现**：网关启动时读取 auth 的契约版本并比对，不一致则 **fail-fast**，日志必须包含：本地版本、对端版本、对端地址、处理建议。给出 diff 与超时/重试策略（auth 未就绪时不得直接判失败，需区分"读不到"与"读到不一致"）。
4. **失败演练步骤**：启动旧版 auth + 新版 gateway → 预期网关启动失败并输出明确日志；再反方向验一次。给出可复制执行的命令序列。
5. **开关与回滚**：一个配置项（如 `cj.security.contract-check.enabled`）用于本地开发关闭校验，并写明生产环境的默认值与关闭后的风险提示。
6. **验收证据**：检查通过时的日志、不一致时的日志与退出码，真实输出。

### 约束条件

- **不一致只允许拒绝启动，不允许降级放行**（不得"记个日志继续跑"）。
- **不得让本地开发与单测因缺 `/actuator/info` 而无法启动**：dev 默认宽松 + WARN，prod 默认严格；单测需能在无对端的情况下通过。
- **不得修改 token 的 claim 结构**（本次只做版本声明与校验，不动格式）。
- 必须区分两种失败：**对端不可达**（重试后 WARN 放行或按配置失败）与**版本不一致**（立即失败）——把二者混为一谈会让灰度发布期误杀。
- 不得声称演练完成，除非附两次反向演练的真实输出。

---

## 6｜E-01　换钥全站 401（高 · 设计缺失）

### 缺口描述

当前 JWT 为**单密钥 HMAC**，不支持多密钥灰度。换密钥后**所有已签发 token 立即失效（含 refresh），全员被迫重登**；且 auth 与 gateway 必须"同一窗口一起换"，只换一个就是**全站 401**。这是发布可测性最差的一类：回滚只能整体回滚，且必然伴随一次全员掉线；既没有"灰度换钥"用例，也没有"只换一个服务"的失败演练。文档已承认该限制。

### 所需背景

- 签发侧：`judge-auth/src/main/java/com/codejudge/auth/common/util/JwtTool.java`
  - L22–23：`@Value("${cj.jwt.secret}")` 注入单一密钥，注释"签名密钥从环境变量 CJ_JWT_SECRET 注入"
  - `createToken` 用 `signWith(key)`；`parse` 用 `verifyWith(key)`——**验签与签发共用同一把钥**，这是无法灰度的根因
- 消费侧：`judge-gateway/src/main/java/com/codejudge/gateway/config/JwtProperties.java`（L17 `cj.jwt.secret`）+ `judge-gateway/src/main/java/com/codejudge/gateway/util/JwtUtils.java`
- 配置：`judge-auth/src/main/resources/application.yml:101`、`judge-gateway/src/main/resources/application.yml:192`，均 `${CJ_JWT_SECRET}`
- 已有承认：`docs/DEPLOYMENT.md#L590`（"单密钥 HMAC，不支持多密钥灰度"、换钥后全员重登）
- 与 A-05 的关系：A-05 定稿"对称 HMAC"，本项**不得改成非对称密钥**，只能在对称体系内做多钥并存

### 期望输出

1. **方案设计**：`kid`（key id）多密钥方案
   - 配置形态建议：`cj.jwt.keys: [{kid, secret, active}]`，**签发只用 active**，**验签遍历全部**
   - token header 写入 `kid`（jjwt 的 `setHeaderParam("kid", ...)`）
   - 旧 token 无 `kid` → 视作默认钥，不得直接 401（向后兼容）
2. **灰度操作 SOP**：分四步，每步写清"做什么 / 观察什么指标 / 什么条件下才进下一步 / 失败怎么退"，覆盖 auth 与 gateway 的发布顺序（谁先、为什么）：
   1. 双钥并存（新钥加入，旧钥仍 active）
   2. 切换 active 到新钥（新 token 用新钥签）
   3. 观察期（旧 token 逐步自然过期）
   4. 撤旧钥（此后旧 token 401，属预期）
3. **代码 diff**：auth 签发侧 + gateway 验签侧，两侧配置语义必须一致。
4. **四条验收用例**（每条给命令与期望）：
   - 双钥窗口内：旧 token 可用 **且** 新 token 可用
   - 撤旧钥后：旧 token **401**，新 token **正常**
   - 无 `kid` 的历史 token：默认钥可用，不因缺字段被拒
   - refresh 路径：换钥窗口内 refresh 仍能续签，不出现"access 有效但 refresh 失效"
5. **单测与回归**：至少覆盖"kid 缺失回退默认钥""多钥遍历命中""active 切换后签发 kid 变化"，给出 `mvn -q -pl judge-auth,judge-gateway test` 输出。

### 约束条件

- **不得引入 RS256 / JWKS / 非对称密钥**——与 A-05 的定稿直接冲突，会制造第二处文档矛盾。
- **旧配置必须仍能启动**：只有 `cj.jwt.secret`（无 `keys`）时按单钥降级运行并打 WARN，不得启动失败。
- **缺 `kid` 的旧 token 不得 401**：回退到默认钥，这是灰度窗口能成立的前提。
- 两侧改动必须同时交付；只改一侧等于制造"只换一个 = 全站 401"的原始问题。
- 密钥值只能来自环境变量，`.env.example` 仅占位。
- 不得声称"换钥演练通过"，除非附上四步 SOP 在本地实跑的真实输出（可脱敏，但需含 token 的 kid 与 HTTP 状态码）。

---

## 附：与对外表述的关系

对外表述中对应的一句是"识别 6 项高优先级缺口并给出可执行断言建议"——**这是当前唯一准确的口径**。只有在本文件 6 段提示词中至少一段实际执行完、并有巡检/命令输出作为证据后，才能在 `docs/review/TESTABILITY-REVIEW-20261107.md` 顶部的「闭环状态声明」里单独登记该项已处置。

登记格式（来自该声明第 3 条要求）：`编号 + 提交号 + 对应断言/命令的执行结果`。**不要批量改声明，一项一条。**
