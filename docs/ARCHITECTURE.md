# CodeJudge 架构说明

> 分布式在线编程评测平台。本文说明**系统边界、模块职责、关键链路与设计取舍**。
> 接口清单见 [`API-REFERENCE.md`](./API-REFERENCE.md)，部署与配置见 [`DEPLOYMENT.md`](./DEPLOYMENT.md)，
> 阶段进度与硬约束见 [`CONTEXT.md`](./CONTEXT.md)。

---

## 1. 系统全景

```
                        ┌──────────────────────────────┐
     浏览器 / JMeter ───▶│  judge-gateway  :9080        │
                        │  · JWT 校验（HMAC 对称）     │
                        │  · 路由转发（静态实例，无 Nacos）│
                        │  · CORS / requestId 透传     │
                        │  · WebSocket 握手（?token=） │
                        └──────┬───────────────────────┘
                               │
     ┌───────────┬─────────────┼─────────────┬────────────┬───────────┐
     ▼           ▼             ▼             ▼            ▼           ▼
 :9081       :9082         :9083          :9084        :9086       :9087
judge-auth  judge-user  judge-problem  judge-submission judge-contest judge-ai
 登录/令牌   用户/RBAC     题目/用例        提交/队列/WS     竞赛/榜单/WS  AI点评/SSE
                                                │                        │
                                     ┌──────────┴──────────┐             │
                                     ▼                     ▼             ▼
                            ┌────────────────┐   ┌──────────────────────────────┐
                            │ RocketMQ 4.9.7 │   │ PostgreSQL 16 + pgvector     │
                            │ judge_submission│   │ judge_ai（向量知识库）        │
                            └───────┬────────┘   └──────────────────────────────┘
                                    │ 消费
                                    ▼
                            judge-worker :9085（可多实例）
                            拉任务 → Docker 沙箱执行 → 回写结果
                                    │
                            ┌───────┴────────────────────────┐
                            │ codejudge/judge-java21          │
                            │ codejudge/judge-python312       │
                            │ codejudge/judge-gcc13           │
                            │ codejudge/judge-go122           │
                            └─────────────────────────────────┘

  共享基础设施：MySQL 8 :3307 ｜ Redis 7 :6380 ｜ MinIO :9000
```

**服务数量**：8 个可运行服务（`9080`–`9087`）。加上基础设施共 12 个容器/进程。

### 为什么没有 Nacos

> 完整论证见 **`docs/ADR-001-服务发现选型.md`**（实测证据 / 五维分析 / 推翻条件 / 三条技术债）。
> 下面只是摘要。

底座（zx-learn）默认关闭 Nacos（`NACOS_ENABLED=false`）。理由：单机房部署下，
注册中心的收益（动态扩缩容发现）远小于它的运维成本（多一个必须健康的组件、启动顺序耦合、
健康检查抖动导致的路由丢失，且它自身需 ≥3 节点才不成单点）。

当前两条寻址链路用的是**两套不同机制**（详见 ADR §2.2）：

| 链路 | 机制 | 扩容一个实例的代价 |
|---|---|---|
| 网关 → 服务 | `uri: ${GW_*_URI}` 静态 http 直连（**单值**） | 改配置 + **重启网关**（入口瞬断） |
| 服务 → 服务 | `@FeignClient(value="judge-xxx")` **服务名** → `SimpleDiscoveryClient` 的 `simple.instances` 静态列表 | 改配置 + 重启该服务（网关不动） |

> 📌 **东西向调用用的是服务名，不是静态 `url`**：实测 5 个 `@FeignClient` 全部使用
> `value = "judge-xxx"`，地址来自 `SimpleDiscoveryClient` 的 `simple.instances`。
> 这个差别很重要：正因为代码里是服务名、走的是 `DiscoveryClient` 抽象，
> **将来若切注册中心，Java 代码零改动**。

**代价**：新增实例必须改配置并重启。判题机多实例（9085/9185/9285）靠 MQ 消费组天然分流，
**不需要服务发现** —— 这是本决策最有力的论据：最需要扩容的组件恰好落在服务发现的作用域之外。

**已知缺口**：网关 JAR 内**没有 `spring-cloud-starter-loadbalancer`**，所以路由当前无法使用
`lb://`（服务端已通过 `judge-api` 传递具备该依赖）。补上这个依赖即可打通多实例通道、
去掉「扩容必须重启网关」这一痛点 —— **代价远低于引入 Nacos**（ADR §6.2 技术债 D1）。

---

## 2. 模块职责

| 模块 | 端口 | 职责 | 关键实现 |
|---|---|---|---|
| `judge-common` | — | 公共库：统一响应、异常体系、用户上下文、守卫注解、MQ 封装、WS 信封、雪花 ID、Redis 序列化 | 被所有服务依赖；`R<T>` / `PageDTO` / `CommonException` |
| `judge-api` | — | 跨服务契约：`Language` 枚举、判题进度/结果消息体 | 只放 DTO，无逻辑；worker 与 submission 共用 |
| `judge-gateway` | 9080 | 统一入口：JWT 校验、路由、CORS、`user-info`/`role-info` 头注入、WS 握手鉴权 | `AuthGlobalFilter` + `JwtProperties` 白名单 |
| `judge-auth` | 9081 | 登录（学员/管理员）、令牌刷新、首次登录强制改密、登出 | HMAC 对称签发；账号密码不在本库，经 Feign 查 `judge_user.user` |
| `judge-user` | 9082 | 用户 CRUD、学员/教师/员工分角色管理、首管理员引导、RBAC 菜单与权限 | `judge_user.user`(BCrypt)；`UserFormDTO` 强校验 |
| `judge-problem` | 9083 | 题目、题面版本快照、测试用例（含隐藏用例）、标签 | 内部接口 `/internal/problems/**` 供判题机取用例 |
| `judge-submission` | 9084 | 提交落库、幂等控制、判题任务下发、判题进度 WS 推送、判题机集群视图 | Redis ZSet 队列 + RocketMQ 双通道；`WorkerViewService` |
| `judge-worker` | 9085 | 判题执行：拉任务 → Docker 沙箱编译运行 → 逐用例比对 → 回写结果 | 无 Controller；MQ 消费 + Redis 心跳（TTL 30s） |
| `judge-contest` | 9086 | 竞赛、报名、实时排行榜、封榜/解封、榜单快照、榜单 WS 推送 | Redis ZSet 三段编码 score；Lua 原子更新 |
| `judge-ai` | 9087 | AI 代码点评（SSE 流式）、RAG 向量知识库、多轮追问 | **WebFlux + Netty**（全项目唯一响应式服务） |

---

## 3. 关键链路

### 3.1 登录与鉴权

```
客户端 ──POST /accounts/login──▶ 网关（白名单放行，不校验 JWT）
                                   │
                                   ▼
                              judge-auth
                                   │ Feign: UserClient.getByCellPhone()
                                   ▼
                              judge-user（查 judge_user.user，BCrypt 校验）
                                   │
                            HMAC 对称签发 accessToken（含 userId/roleId claim）
                                   ▼
                            返回 { accessToken, refreshToken, userId, username }
```

之后每个请求在网关校验 JWT，把 `userId`/`roleId` 写入 **`user-info` / `role-info` 请求头**下发。
下游服务通过 `UserContext`（ThreadLocal）读取，因此**下游服务不解析 JWT**。

> ⚠ `judge-ai` 是 WebFlux，`UserContext`（ThreadLocal）在响应式线程模型下必然为空，
> 所以它改用 `AiIdentity.from(ServerHttpRequest)` 直接读 `user-info` 头，
> 并且**不启用** `InternalOnlyGuard` / `OwnerAccessGuard` —— 这两个守卫在 WebFlux 下
> 会把所有外部请求误判为内部调用而放行。

### 3.2 提交 → 判题 → 回传（核心链路）

```
① POST /submissions
   ├─ 幂等快路径：Redis key judge:submission:idempotent:{userId}:{problemId}:{contestId}:{codeHash}
   ├─ 落库 judge_submission + judge_task（status=0 待判）
   ├─ 双通道投递：Redis ZSet（judge:judge:queue:zset，低延迟）+ RocketMQ judge_submission:CREATED（可靠）
   └─ 立即返回 submissionId（异步，不阻塞用户）

② judge-worker（可多实例）
   ├─ Redis 心跳 judge:worker:heartbeat:{workerId}（TTL 30s，过期即视为离线）
   ├─ SETNX 任务锁（必须在 DB CAS 认领成功之后再挂，否则会出现「锁住了但没人执行」）
   ├─ 拉题目判据 GET /internal/problems/{id}/judge-info（含隐藏用例）
   ├─ Docker 沙箱：编译 → 逐用例运行（CPU/内存/时间限制）
   └─ 回写结果 + 投递 judge_submission:RESULT

③ 进度推送
   └─ WebSocket /ws/submissions/{id}：START → 逐用例 CASE → END（含 verdict）
```

**判题结果语义**：`AC / WA / TLE / MLE / RE / CE / SE`。

### 3.3 竞赛排行榜

```
参赛者提交 → judge-contest 通过内部接口取结果 → 计算 score
           → Lua 脚本原子更新 ZSet judge:contest:rank:{contestId}
           → WebSocket /ws/contests/{id}/rank 广播增量
```

**score 三段编码**（一维 ZSet 表达「过题数优先 → 罚时少优先 → 越早通过越好」）：

```
score = 权重 × 10^12 + (999999 − 罚时秒) × 10^6 + (999999 − 末次通过偏移秒)
        └─ 过题数   ┘   └──── 罚时（越少越大） ────┘   └──── 达成时间（越早越大） ────┘
权重 ≤ 8999：保证 权重×10^12 不超过 double 的安全整数位（2^53）
```

**封榜**：封榜瞬间对每个用户的 ZSet 做一次原子快照（`judge:contest:user:frozen:*`），
再用 `ZUNIONSTORE` 生成冻结榜。解封时无需合并计算 —— 这是「封榜一致性」的关键：
实时榜照常更新，冻结榜是独立副本，两者互不干扰。

### 3.4 AI 点评（SSE 流式）

```
POST /ai/review/stream  （Accept: text/event-stream）
  │
  ├─ 事件序列：START → RETRIEVAL → DELTA×N → END
  ├─ 错误走**事件**不走状态码：
  │    订阅前失败 → application/json + R{401}
  │    订阅后失败 → text/event-stream + event:ERROR （HTTP 仍 200）
  └─ 断线重连：Last-Event-ID 按 **seq** 过滤回放（不按下标）

RAG 检索：智谱 embedding-3 → pgvector（vector(1024) + HNSW）
内部契约：GET /internal/submissions/{id}/review-context?maskHidden=
          ⚠ 学员调用必须 maskHidden=true，否则可借 AI 问出隐藏用例的期望输出
```

---

## 4. 数据存储分工

| 存储 | 端口 | 承载 | 选型理由 |
|---|---|---|---|
| MySQL 8 | 3307 | `judge_auth` / `judge_user` / `judge_problem` / `judge_submission` / `judge_contest` | 事务性数据；MyBatis-Plus 生态 |
| Redis 7 | 6380 | 判题队列 ZSet、幂等 key、worker 心跳、竞赛榜 ZSet、任务锁 | 榜单与队列都是排序/原子计数场景 |
| PostgreSQL 16 + pgvector | 5433 | `judge_ai`（`knowledge_chunk` / `ai_review` 的 embedding） | MySQL 无原生向量索引；HNSW 上限 2000 维 |
| RocketMQ 4.9.7 | 9877 / 10919·10921·10922 | 判题链路 topic `judge_submission`（Tag：CREATED/RETRY/RESULT） | 削峰 + 重试 + 死信 |
| MinIO | 9000 | 超长代码、题目附件 | — |

> **为什么 PG 只给 judge-ai 用**：向量检索是它的独占需求。把整库迁到 PG 会牵动全部模块
> 且无收益；MySQL + PG 并存只多一个容器，边界清晰。

---

## 5. 横向机制

| 机制 | 实现位置 | 说明 |
|---|---|---|
| 统一响应 | `judge-common` `R<T>` | `{code, msg, data, requestId}`；**`code=200` 为成功**（不是 1），判定走 `R.success()` |
| 异常体系 | `CommonException` + `ErrorCode` | 无 `BizException`；`BadRequest` / `Unauthorized` / `Forbidden` / `AccountDisabled` / `DbException` |
| 分页 | `PageDTO` + `PageQuery` | **无 `PageResult`** |
| 鉴权守卫 | `OwnerAccessGuard`（归属）、`InternalOnlyGuard`（内部接口防外呼）、`RequireRole` | judge-ai 不适用（见 §3.1） |
| 链路标识 | `requestId` 透传 | 网关生成，全链路日志可串 |
| ID 生成 | 雪花 ID | 跨库唯一，避免自增暴露业务量 |
| 配置 | 环境变量前缀 `CJ_*` | **零硬编码**：密码 / JWT 密钥 / LLM Key 全部外部注入 |
| 监控 | Micrometer → `/actuator/prometheus` | 8 服务全量暴露；`application` 标签统一；见 `deploy/monitoring/` |

---

## 6. 端口规划（避让 zx-learn）

本机同时存在 zx-learn（8080–8095 / 3306 / 6379 / 5432 / 9876 / 10909 / 10911 / 10912 / 18080），
CodeJudge 整体换段：

| 用途 | zx-learn | **CodeJudge** |
|---|---|---|
| 服务端口段 | 8080–8095 | **9080–9087**（判题机多实例 9085/9185/9285） |
| 前端 | — | **5174** |
| MySQL | 3306 | **3307** |
| Redis | 6379 | **6380** |
| PostgreSQL | 5432 | **5433** |
| RocketMQ namesrv | 9876 | **9877** |
| RocketMQ broker | 10909/10911/10912 | **10919 / 10921 / 10922** ⚠ 最易漏改 |
| RocketMQ Dashboard | 18080 | **18081** |
| MinIO | — | 9000 / 9001 |
| Prometheus | — | **9090** |
| Alertmanager | — | **9093** |
| Grafana | 3000 | **3001** |

> ⚠ RocketMQ broker 的端口必须与 `deploy/rocketmq/broker.conf` 的 `listenPort=10921`
> 及 `fastRemotingPort=10919` / HA `10922` 严格一致，改一处不改另一处会导致 broker 起不来。

---

## 7. 设计取舍记录

| 决策 | 取舍 |
|---|---|
| 关闭 Nacos | 省一个必须健康的组件；代价是新增实例要改配置 —— **完整论证见 `ADR-001-服务发现选型.md`** |
| `judge-common` **不引入** Redisson | P3/P4 的并发场景已由 MySQL 唯一索引 / MQ 消费组 / Redis ZSET 覆盖，不需要分布式锁。触发重引入的条件：出现跨服务的「先查后改」临界区 |
| 判题走「Redis 队列 + MQ」双通道 | 队列给低延迟、MQ 给可靠性；代价是两条路径都要幂等，靠 `judge_task` 状态 CAS 收敛 |
| 教师**不做**「申请—审核」流程 | 管理员已有 `POST /teachers/register` 与 `POST /users` 两条开号路径，无产品缺口 |
| 取消匿名教师自助注册 | 保留 STAFF-only 收紧（行为变更，2026-09 阶段决策） |
| SSE 错误走事件不走状态码 | 订阅建立后 HTTP 头已发出，无法再改状态码；客户端必须处理 `ERROR` 事件 |
| PG 表插入用 `INSERT ... RETURNING id` | PG 驱动在 `RETURN_GENERATED_KEYS` 下返回整行，`KeyHolder.getKey()` 抛异常 → 静默数据损坏（P5 实测缺陷） |

## 队列对账与自愈设计（LAUNCH-CHECKLIST I2）

实现：`judge-submission/service/JudgeCompensationService.java`

### 为什么需要对账

判题任务的「入队」发生在 Redis ZSet（`judge:judge:queue:zset`），而任务状态在 MySQL。
两条存储写入路径不同，任何一环异常（MQ 丢消息、worker 崩在判一半、死信分支漏清理）都会留下
「任务已终态但 ZSet 里还有它」的**僵尸成员**——表现为队列积压虚高、`/workers` 集群视图失真、
滞留重发兜底反复空转。

### 机制（三层）

| 层 | 触发 | 动作 |
|---|---|---|
| 主路径 ZREM | `ResultEventHandler` 收到判题结果 | 收到即 `ZREM`（终态成员立刻出队） |
| 死信分支 ZREM | `JudgeEngine.failTask` / `JudgeCompensationService.deadLetter` | 任务进死信时同步 `ZREM`，不让死信任务留在待判队列 |
| 队列对账扫描 | `queueReconcileScan()` 定时（每 60s） | 按入队时间升序取最老一批（`RECONCILE_BATCH`），逐个核对 MySQL 任务状态：已终态 / 不存在 / 非法成员 → 批量 `ZREM`，日志给出摘除数量 |

### 自愈闭环

对账扫描是**只摘除、不重投**的（重投由「滞留重发 pending-rescue」负责，两套机制各管一半）：

- ZSet 有、任务无/已终态 → 对账摘除（清垃圾）；
- 任务存在、未终态、但迟迟没人认领 → 滞留重发补投递（补数据）。

`WorkerViewService` 的积压指标口径**依赖**对账：若对账失效，`judge_queue_backlog` 会只增不减
—— 监控上「积压持续单边上涨 + worker 全部空闲」就是对账失效的指纹。

---

## 附录：JWT 签名算法取舍（为什么不用 RS256）

**结论先行**：本系统令牌一律 **HMAC-SHA 对称签名**（不是非对称）。签发方 `judge-auth` 与验签方
`judge-gateway` 共享同一组密钥（单钥 `CJ_JWT_SECRET`，或 E-01 的 `CJ_JWT_KEYS_<n>_*` 多钥），两端配置必须一致。

| 事实 | 证据 |
|---|---|
| 密钥构造 | `judge-auth/.../util/JwtTool.java`：`Keys.hmacShaKeyFor(secret.getBytes(UTF_8))` |
| 签发 / 验签 | 同文件 `signWith(activeKey)` / 遍历 `Jwts.parser().verifyWith(key)`（多钥时逐一尝试） |
| 密钥来源 | `judge-auth/src/main/resources/application.yml`、`judge-gateway/src/main/resources/application.yml`，`cj.jwt.secret` / `cj.jwt.keys` |
| 具体强度 | `Keys.hmacShaKeyFor` 按密钥长度选算法：≥64 字节 → HS512、≥48 → HS384、≥32 → HS256。当前密钥 64 字节，**实际为 HS512**（可由 token 头 `{"alg":"HS512"}` 复核） |

### 为什么对称就够

非对称签名（RS256 一类）的核心收益是「验签方只需公钥」——适用于**验签方不受信或数量不可控**
的场景（第三方接入、浏览器直连验签、多租户）。本系统恰好相反：

1. **验签方只有网关一个**，且与签发方同处受控内网、同一发布单元；
2. **没有第三方需要独立验签**：下游服务不解析 JWT，只认网关注入的 `user-info` / `role-info` 头；
3. 对称签名的开销与密钥管理面都更小：一枚 32 字节以上密钥、一个环境变量，无需密钥对生成、
   存储、轮换与分发链路。

换言之，引入非对称只会增加一套密钥生命周期管理，却换不到它唯一的收益。

### `/jwks` 为什么删除（2026-09-25）

HMAC 是**对称**算法：`Key.getEncoded()` 的内容就是签名密钥本体，**没有可以安全公开的"公钥"**。
此前的 `/jwks` 端点把它 base64 后匿名发出，等价于向任何人交出「签发任意身份令牌」的能力，
属于认证体系级漏洞。修复方式是直接删除端点、白名单与网关路由（方案 A），而不是改为
非对称以保留该端点 —— 见 `CORE-IMPLEMENTATION.md` §4.4。当前 `GET /jwks` 返回 **404**。

### 密钥轮换（E-01：kid 多钥灰度，2026-11-11 落地）

- **多钥并存**：配置 `cj.jwt.keys`（env 形式 `CJ_JWT_KEYS_<n>_KID / _SECRET / _ACTIVE`）。签发只用
  `ACTIVE=true` 的那把并在 header 写 `kid`；验签**遍历全部密钥**，无 `kid` 的历史 token 同样命中
  → 支持「旧钥继续验签、新钥签发」的平滑过渡，观察期内不产生全员 401。
- **单钥仍兼容**：只配 `CJ_JWT_SECRET` 时退化为单钥模式（启动打 `[jwt-keys] 单钥模式` WARN），
  语义与改造前一致；该模式换钥仍需双端同窗口 + 全员重登。
- **灰度 SOP**（双钥并存 → 切 active → 观察期 → 撤旧钥，含每步观测指标与回退方式）见
  `DEPLOYMENT.md` §8.2。
- **触发重引入非对称的条件**：出现「验签方不再受信」的需求（例如开放平台让第三方服务
  自行校验令牌），或需要多签发方。届时才值得放弃对称、改为非对称 + JWKS。

### 三条可复核断言

```bash
# ① 端点已删除：期望 HTTP 404
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9080/jwks

# ② 篡改签名：期望 HTTP 401（取一枚合法 accessToken，改掉末位字符后访问受保护接口）
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <被篡改的 token>" \
     http://localhost:9080/accounts/me/capabilities

# ③ 登录限流：连续错误密码登录，期望第 6 次起 HTTP 429（令牌桶 2 req/s、突发 5）
for i in $(seq 1 8); do
  curl -s -o /dev/null -w "$i:%{http_code}\n" -H 'Content-Type: application/json' \
       -d '{"cellPhone":"13900000001","password":"wrong-password"}' \
       http://localhost:9080/accounts/login
done
```

> ⚠ 两条测试陷阱（2026-11-11 实测踩到）：
> - 断言②**不能改签名末位**：HS512 签名 64 字节 → base64url 86 字符，末位字符含 4 个填充位，
>   改它可能不改变解码后的签名，得到「篡改后仍 200」的假结论。要改签名**中段**字符。
> - 断言③**必须并发发起**：命中令牌桶后的补充速率是 2/s，顺序发 10 次会被补充追平而永不耗尽。
>   实测 10 并发 → 5×200 + 5×429。测完记得删掉 Redis 里的
>   `request_rate_limiter.{rate:login:<客户端IP>:anon}.*`，否则本机登录会被限流一分钟左右。
