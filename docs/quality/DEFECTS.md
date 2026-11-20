# CodeJudge 缺陷与修复记录

> 快照日期：2026-11-16
> 分级口径：原审查报告使用“高 / 中 / 低”，本文映射为 **P0–P1 / P2 / P3**；P0 专指可直接提权、接管账号或破坏数据完整性的缺陷。
> 所有条目均来自仓库内审查或压测记录，未新增未发生的问题。逐条来源见每行“依据”。

## 1. 统计

| 级别 | 项数 | 修复 | 暂缓 |
|---|---:|---:|---:|
| P0–P1（高危） | 7 | 7 | 0 |
| P2（中危） | 17 | 16 | 1 |
| P3（低危） | 10 | 10 | 0 |
| 压测执行缺陷 | 3 | 3 | 0 |
| 配置 / 依赖整改 | 12 | 9 | 2 保留 + 1 无实际整改项 |

## 2. P0–P1（高危，全部已修复）

| 编号 | 级别 | 现象 | 根因 | 修复 | 验证 |
|---|---|---|---|---|---|
| S1 | P0 | 匿名 `GET /jwks` 可直接拿到 JWT 签名密钥，可离线伪造任意身份 token | HMAC 为对称签名，`getEncoded()` 返回密钥本体；该端点还在免鉴权白名单且全仓无消费方 | 删除 Controller、工具方法、白名单项、网关路由与 Feign 方法；单测改为断言 `type` claim | `/jwks` 返回 404；篡改签名返回 401（原 token 200 对照）（`docs/review/A-05-VERIFY.md`） |
| S2 | P0 | 30 天有效期的 refresh token 放进 `Authorization` 头即可全权访问 | 网关 `parseIdentity` 不校验 `type` claim；刷新端点也不区分 token 类型 | 网关只放行 `type=access`；`/accounts/refresh` 只接受 `type=refresh` | 登录链路 43/43；契约测试覆盖 token 类型（`docs/review/E-01-KEY-ROTATION-DRILL.md`） |
| S3 | P0 | 任意学员传管理员 id 即可重置管理员密码（IDOR → 账号接管） | `PUT /users` 无角色注解，目标取请求体 `form.id`，且复用可改密码的 `updateUser` | 目标强制取 `UserContext.getUserId()`；新增 `updateProfile` 只放行 name/icon/email | `verify-authz.py` 56/0 含水平越权断言（`docs/REVIEW-2026-09-25.md` §二） |
| S4 | P0 | `sortBy` 参数原样进入 `ORDER BY`，存在布尔盲注，影响全部分页接口 | MyBatis-Plus `OrderItem.column` 为字符串拼接 | 列名白名单正则 `^[a-zA-Z][a-zA-Z0-9_]*$`，不匹配回退默认列；同时钳制分页大小与页码 | 鉴权/注入回归 56/0；分页边界用例随 `mvn verify` 通过（同上） |
| S5 | P1 | 管理端登录别名接口完全不受登录限流保护，可绕过防爆破 | 限流谓词只匹配 `/accounts/login` | 谓词同时匹配 `/accounts/login` 与 `/accounts/admin/login` | preflight C1：12 次瞬时登录 → 5×200 + 7×429（`docs/LAUNCH-READINESS.md` §C） |
| H1 | P1 | AI 点评高并发下事件循环被阻塞，历史向量索引静默失效 | Redis 写、JDBC、Embedding `block()`、`doOnCancel` 落库全在 Netty I/O 线程 | 生成链路切 `boundedElastic`，阻塞点全部移出事件循环 | AI 点评链路 46/0；评测 40 样本正常产出（`docs/LAUNCH-READINESS.md` §G） |
| F1 | P1 | 19 位雪花 ID 被转 Number 后精度截断，AI 点评/历史查询必然 404 且被吞成空列表 | 前端两处 `Number()` 转换超过 `2^53` | 删除转换、字符串透传；同类残留 9 处一并清零 | 三浏览器 15/15（含 AI 点评链路）；Vitest 167/167（`docs/compat/COMPAT-MATRIX-20261107.md`） |

## 3. P2（中危：16 修 / 1 暂缓）

| 编号 | 现象 | 根因 | 修复 | 验证 |
|---|---|---|---|---|
| M1 | 判题结果详情出现重复用例记录 | `finishTerminal` 先插结果后 CAS 且无事务，CAS 失败留脏行 | CAS 与插行收进同一事务，失败整体回滚 | `mvn verify` 358/0；判题链路 21/0 |
| M2 | 重判后出现“PENDING + 旧 verdict”并残留旧租约 | `updateById` 默认忽略 null，无法复位字段 | 改 `LambdaUpdateWrapper` 显式 `set(null)`，复位逻辑抽独立方法 | 同上 |
| M3 | 已成功提交可能被死信处理改写成 FAILED 并投死信 | 忽略 CAS 返回值 | `updated != 1` 直接返回，改写只在 CAS 命中分支执行 | 同上 |
| M4 | 封榜中途崩溃后 48h 内无法自愈 | SETNX 锁先于工作获取 | 工作段 try/catch，异常时删锁回滚后重抛 | 竞赛相关单测随 `mvn verify` 通过 |
| M5 | 重判/创建在事务内发起 Feign，放大连接池占用 | 远程校验与写库同事务 | 远程校验移到事务外，写库经自代理进入事务 | 同上 |
| M6 | LLM/Embedding 调用无超时可永久挂死消费线程 | WebClient 未配响应超时 | LLM 120s、Embedding 30s + `block(Duration)` 双保险 | AI 链路 46/0 |
| M7 | 知识入库中途失败导致半写/丢失 | 先删旧再逐片向量化 | 先全部向量化成功，再删旧并批量写入 | 同上 |
| M8 | 沙箱墙钟强杀只杀 CLI，容器残留 | 只 kill `docker` 客户端进程 | 容器命名 `cj-sbx-<uuid>`，超时补 `docker rm -f` | 判题链路 21/0 |
| M9 | 任意登录用户可查任意人手机号 | `GET /users/{id}` 无归属校验 | `OwnerAccessGuard`：本人/员工/内部调用放行 | 鉴权 56/0 |
| M10 | 改密缺字段返回 500，新密码可设 1 位 | 无判空与长度校验 | 判空 + 6~64 位（对齐 BCrypt 72 字节上限） | 鉴权 56/0 |
| M11 | refresh token 随响应体下发，架空 HttpOnly | VO 字段未屏蔽 | 字段加 `@JsonIgnore`；登录断言同步反转 | 登录 43/0 |
| M12 | Cookie 的 Secure/Domain 属性设置了但不生效，多枚 Cookie 被吞 | 格式串位置错、`setHeader` 覆盖 | 改 `addHeader` 并按需拼接全部属性 | 登录 43/0 |
| M13 | 生产会暴露完整接口清单 | springdoc 未做 profile 分离 | **暂缓**：本地验收依赖 `/doc.html`；已改为生产开关并在部署清单置 false | `docs/LAUNCH-CHECKLIST.md` D5 |
| M14 | 监控危险面：Prometheus admin API 无认证、Grafana 匿名默认开放 | 默认配置面向本地 | 移除 admin API；Grafana 匿名默认 false（fail-closed） | `docs/LAUNCH-CHECKLIST.md` D7 |
| M15 | 中间件端口绑定全部网卡，root 口令服务暴露到局域网 | 容器端口未限制绑定地址 | 默认仅绑回环地址，`.env` 可覆盖 | `docs/LAUNCH-CHECKLIST.md` D1 |
| F2 | 监控页“Top URI”图表永远为空 | 普通对象被 `computed` 引用，无响应式依赖 | 改 `ref({})` 并读 `.value` | 前端 167/167 |
| 暂缓-1 | 登出/改密/禁用后已签发 refresh token 仍有效 | 无轮换与吊销机制 | **暂缓**：涉及登录态语义变更，需拍板 | `docs/REVIEW-2026-09-25.md` §六 |

> 原报告将该暂缓项以文字记录、**未给编号**，本文不新造编号，记为“暂缓-1”；M 系列与 F2 共 16 项均已修复。

## 4. P3（低危，全部已修复）

| 编号 | 现象 | 根因 | 修复 | 验证 |
|---|---|---|---|---|
| L1 | CJK 代码按字符数校验，落库超 TEXT 64KB 报 500 | 用 `String.length()` 而非 UTF-8 字节数 | 改按 UTF-8 字节数校验 | `mvn verify` 358/0 |
| L2 | 进程重启后用户可能被永久限流 | INCR/EXPIRE 非原子，留下无 TTL key | TTL 缺失时补偿补期 | 鉴权回归 56/0 |
| L3 | 毒消息重投 16 次 | 反序列化异常外抛 | 解析失败记录后丢弃 | 判题链路 21/0 |
| L4 | AI 点评毒消息重复消费 | 同上 | 同上 | AI 链路 46/0 |
| L5 | 竞赛用户名缓存条目永不删除 | 只有逻辑 TTL，无清理 | 超 10 000 条清理过期项 | `mvn verify` |
| L6 | 并发报名偶发 500 | select-then-insert 竞态 | 捕获唯一键冲突后回查幂等返回 | 竞赛单测 |
| F3 | 复用组件时三个详情页展示旧实体，提交记到旧题目 | 未监听 `route.params.id` | 补 `watch` 重载/重订阅 | 前端 167/167；E2E 8/8 |
| F4 | 服务端重启后判题进度永久卡死 | WS 重连 seq 守卫丢弃重启后的推送 | SNAPSHOT 恒放行并重置基线 | E2E 8/8 |
| F5 | 快速翻页时旧响应覆盖新数据（5 个页面） | 无请求序号守卫 | 加 `seq` 守卫 | 前端 167/167 |
| F6 | 榜单重连耗尽无提示；网络故障被当成登录过期踢人 | 错误未分类 | 补 `status.error`；区分网络类错误并保留登录态 | 前端 167/167 |

## 5. 压测执行中发现的缺陷

| 编号 | 级别 | 现象 | 根因 | 修复 | 验证 |
|---|---|---|---|---|---|
| T1 | P1 | 3 实例不限并发时，25 条正确提交有 18 条被判 TLE | 消费线程数随实例数放大，判题机资源争抢导致墙钟超时 | 引入 `CJ_MQ_CONSUME_THREADS`，约束“实例数 × 线程数 ≤ CPU 核数”；3 实例各 4 线程 | 25/25 AC，吞吐 0.96 题/s（`docs/PERF.md` §3.7） |
| T2 | P2 | 恒负载计划配 180s 实际跑了 13 分钟、226 万样本、结果文件 400MB | JMeter `<boolProp>` 不做函数替换，`${__P(...)}` 被当成 false，“无限循环 + 调度器关闭” | 改字面量 `true`；`run-perf.py` 增加墙钟看门狗，超时整树杀 | 重跑得到规范 180s 窗口（`docs/PERF.md` §3.5 注） |
| T3 | P2 | 健康状态下连接池告警被真实触发，淹没真正故障 | 判据 `pending > 5` 把正常排队当异常；实测峰值 29 但错误率 0.000%、获取连接仅 7.6ms | 改用平均获取连接耗时 > 0.2s 或 acquire 超时 > 0 | 阈值已换并记录（`docs/PERF.md` §四） |

## 6. 附录：配置 / 依赖整改（非运行缺陷，不参与 P 级统计）

| 编号 | 问题 | 处理 |
|---|---|---|
| C1 | 网关 Redis 密码默认空，与其他服务 fail-fast 不一致 | 改为必填 |
| C2 | 沙箱镜像默认名三处不一致 | 统一默认值 |
| C3 | 判题超时/输出上限两处默认值漂移 | 对齐 60s / 1024KB |
| C4 | `CJ_MQ_CONSUME_THREADS` 未在模板中声明 | 补条目 + 容量公式 |
| C5 | AI 相似度阈值注释与行为矛盾 | 注释改为如实描述 |
| C6 | MySQL 驱动使用已废弃坐标 8.0.23 | 迁移到 `com.mysql:mysql-connector-j` 8.3.0 |
| C7 | 公共模块重复钉死 RocketMQ 版本 | 交父 pom 统一管理 |
| C8 | 前端残留已删除端点的死代码 | 移除 |
| C9 | compose 中 MQ 链路容器无健康检查 | **保留现状**：改动收益低，已记录 |
| C10 | 无实际整改项 | — |
| C11 | 分页大小常量为死代码 | 随 S4 一并落实 |
| C12 | access token 存 localStorage、WS token 走 URL | **保留现状**：既定取舍，XSS 面已收敛 |

## 7. 遗留未修（需拍板）

| # | 事项 | 影响 | 建议 |
|---|---|---|---|
| 1 | refresh token 轮换 + 吊销 | 登出/改密/禁用后 token 仍有效 | Redis 存 `refresh:{userId} -> jti`，刷新时轮换 |
| 2 | 生产关闭接口文档 | 暴露 API 结构 | 部署时置 `CJ_SPRINGDOC_ENABLED=false` |
| 3 | 应用日志无滚动 | 磁盘可被写满 | 容器日志驱动限额或 logrotate |
| 4 | Python 语法错误判为 RE 而非 CE | 判题语义偏差 | 沙箱增加 `py_compile` 预检 |
| 5 | Java 堆内 MLE 判为 RE | 判题语义偏差 | 按语言特化捕获 OOM |

逐条修复细节与代码位置见 `docs/REVIEW-2026-09-25.md`、`docs/PERF.md` §3.5/§3.7/§四。
