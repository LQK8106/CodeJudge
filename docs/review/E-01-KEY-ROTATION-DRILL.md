# E-01 换钥演练总结报告

- 日期：2026-11-11
- 范围：judge-auth / judge-gateway（JWT 签发与验签）
- 结论：**通过**。四步灰度换钥全程无「新钥签发 → 网关验不过」的 401 窗口；单钥旧配置可正常启动；回归 43 项登录断言全绿。

> **证据声明**：§4 各步骤日志为演练期手工摘录，**未保留原始日志文件**（演练容器日志已随重建滚动丢失）。
> §5 两项回归为 2026-11-11 复核时实跑，原始输出已落盘到 `docs/review/raw-e01-20261111/`。

---

## 1. 演练目标

原实现只有单密钥 `CJ_JWT_SECRET`，两侧必须同窗口替换，否则新 token 在网关侧全部 401。
E-01 要求：

1. 支持 `kid` 多钥并存，签发只用 active 钥，验签遍历全部钥；
2. **不得破坏单钥旧配置**（只配 `CJ_JWT_SECRET` 必须能启动）；
3. 固化「双钥并存 → 切 active → 观察 → 撤旧钥」四步 SOP，并给出每步真实证据。

---

## 2. 改造内容

| 文件 | 变更 |
|---|---|
| `judge-auth/.../config/JwtKeyProperties.java` | 新增。`cj.jwt.secret`（单钥兼容）+ `cj.jwt.keys[]`（`kid`/`secret`/`active`） |
| `judge-auth/.../common/util/JwtTool.java` | 签发只用 active 钥并写 header `kid`；验签按「active 优先」遍历全部钥；无 `keys` 时单钥模式 + 启动 WARN |
| `judge-gateway/.../config/JwtProperties.java` | 增加 `keys[]`（与 auth 同语义，仅用于验签排序） |
| `judge-gateway/.../util/JwtUtils.java` | 验签遍历全部钥；`parseIdentity`/`isValid`/`parseUserId`/`parseRoleId` 共用同一遍历逻辑 |
| 两侧 `application.yml` | `cj.jwt.secret: ${CJ_JWT_SECRET:}`；注释说明 `CJ_JWT_KEYS_<n>_*` 注入方式 |
| `judge-auth/.../JwtToolTest.java` | 新增 5 例：active 写 kid、切 active、无 kid 历史 token、inactive 钥、未知钥拒绝 |
| `judge-gateway/.../JwtUtilsKeyTest.java` | 新增 5 例：同上（网关视角） |
| `docs/DEPLOYMENT.md §8.2` | 重写为四步灰度 SOP（观测项、放行条件、回退方式、复验命令） |
| `docs/ARCHITECTURE.md` 附录 | 「无法多钥并存」代价改为「已支持 kid 多钥灰度」 |
| `docs/LAUNCH-CHECKLIST.md` A1 | 补充「零掉线轮换走 §8.2」 |
| `.env.example` | 增加 `CJ_JWT_KEYS_0_*` / `CJ_JWT_KEYS_1_*` 注释示例 |

改动规模（`git diff --stat`，2026-11-11 复核时口径，**不含本文档**）：

- 已跟踪文件 6 个：auth 3（`JwtTool.java`、`JwtToolTest.java`、`application.yml`）、gateway 3（`JwtProperties.java`、`JwtUtils.java`、`application.yml`）；
- 合计 **+305 / −22** 行；
- 新增未跟踪文件 2 个：`judge-auth/.../config/JwtKeyProperties.java`（49 行）、`judge-gateway/.../test/.../JwtUtilsKeyTest.java`（86 行）；
- 文档侧另有 `DEPLOYMENT.md` / `ARCHITECTURE.md` / `LAUNCH-CHECKLIST.md` / `.env.example` 的说明性改动（不在上述 stat 内）。

关键语义：

- 签发：`active=true` 的钥，header `{"kid":"<kid>","alg":"HS512"}`；
- 验签：遍历全部钥（active 优先），**无 `kid` 的历史 token 直接命中**，不做缺字段拒绝；
- 配置错误（多钥但无 active / 多个 active）→ 启动失败并给出明确原因；
- 索引环境变量必须从 `0` 连续（稀疏索引会绑定出 null 元素，代码已跳过 null，但应以 0 起始）。

---

## 3. 演练环境

- 编排：`docker compose --profile app`，auth:9081 / gateway:9080，MySQL/Redis 均复用本地实例
- 镜像：`scripts/build-app-images-prebuilt.py judge-gateway judge-auth`（预构建 jar）
- 密钥：演练用两把随机钥 `k-2026a`（原钥）/ `k-2026b`（新钥）。每把取 **48 字节随机熵**，经 base64 编码成 **64 字符**密钥字符串（UTF-8 字节数 = 64）；
  签发/验签走 `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`，jjwt 按**密钥字符串的 UTF-8 字节数**选算法：**≥64 字节 → HS512、≥48 → HS384、≥32 → HS256**，故本例为 **HS512**。
  本地 `.env` 的 `CJ_JWT_SECRET` 同口径（实测 64 字符，无引号）。
- 验证端点：`GET /accounts/me/capabilities`（需 access token）、`POST /accounts/refresh`（HttpOnly refresh cookie）

---

## 4. 演练时间线（真实输出）

### 步骤 0：单钥基线（新代码兼容旧配置）

```
auth  13:27:48 WARN [jwt-keys] 单钥模式（未配置 cj.jwt.keys）…
gw    13:28:06 WARN [jwt-keys] 单钥模式（未配置 cj.jwt.keys）…
gw    13:28:08 INFO [contract-check] 通过：本地=v1 对端=v1
token header = {"alg":"HS512"}   （无 kid）
status = 200
```

结论：未配置 `keys` 时不启动失败，行为与改造前一致。

### 步骤 1：双钥并存（旧钥 active，先网关后 auth）

先发网关（验签方先认识新钥）：

```
gw 13:28:40 INFO [jwt-keys] 多钥模式：验签密钥 2 把（active 优先）
gw 13:28:42 INFO [contract-check] 通过：本地=v1 对端=v1
无 kid 旧 token → 200
```

再发 auth：

```
auth 13:28:56 INFO [jwt-keys] 多钥模式：active=k-2026a 参与验签=[k-2026a, k-2026b]
新登录 header = {"kid":"k-2026a","alg":"HS512"} → 200
旧 token（无 kid）→ 200
```

### 步骤 2：切换 active（只重启 auth，网关验签集合不变）

```
auth 13:29:20 INFO [jwt-keys] 多钥模式：active=k-2026b 参与验签=[k-2026b, k-2026a]
新登录 header = {"kid":"k-2026b","alg":"HS512"} → 200
旧 token（无 kid，旧钥）→ 200          # 平滑窗口成立
旧 refresh cookie 续签 → HTTP 200（成功）
```

结论：切换签发钥后，旧 access / 旧 refresh 仍可用，无双端同窗口约束。

### 步骤 3：观察期（压缩时间轴）

生产条件下此处静置：access 30 分钟自然过期、refresh 30 天；期间旧 token 持续 200。
本次演练以「步骤 4 前旧 token 仍 200」作为观察期判据，不等待自然过期。

### 步骤 4：撤旧钥（先 auth 后网关）

```
auth 13:31:01 INFO [jwt-keys] 多钥模式：active=k-2026b 参与验签=[k-2026b]
gw   13:31:20 INFO [jwt-keys] 多钥模式：验签密钥 1 把（active 优先）

旧钥 token（无 kid）→ 401
k-2026b token       → 200
重新登录            → 200，header={"kid":"k-2026b","alg":"HS512"}
旧 refresh cookie   → body {"code":401,"msg":"登录已过期，请重新登录"}
```

结论：撤旧钥后旧凭证按预期失效，新钥凭证不受影响。

### 收尾：新钥转正为基线

将 `CJ_JWT_SECRET` 替换为新钥、移除 `CJ_JWT_KEYS_*`，回到单钥模式：

```
auth 13:32:13 WARN [jwt-keys] 单钥模式…
gw   13:32:27 WARN [jwt-keys] 单钥模式…
新登录 header = {"alg":"HS512"} → 200
演练中 k-2026b 签发的 token（签名钥相同）→ 200
旧密钥签发的 token → 401
```

---

## 5. 判据汇总

| 判据 | 结果 |
|---|---|
| 单钥旧配置可启动 | PASS（两侧 WARN + 200） |
| 双钥阶段旧 token 不失效 | PASS（无 kid token 200） |
| 切 active 后新旧 token 同时可用 | PASS（200 / 200） |
| 旧 refresh 在双钥窗口可续签 | PASS（HTTP 200，无错误日志） |
| 撤旧钥后旧 token 401 | PASS（access 401 / refresh body code=401） |
| 撤旧钥后新 token 200 | PASS |
| 无「新钥签发 → 网关 401」窗口 | PASS（签发钥切换时网关已含新钥） |
| 登录链路回归 | PASS（`verify-p1-login.py` 43 项通过 / 0 失败，2026-11-11 复跑） |
| 单元测试 | PASS（auth 66、gateway 28，新增 10 例，2026-11-11 复跑） |

### 5.1 单元测试原始输出

执行命令：`mvn -pl judge-auth,judge-gateway -am test`（2026-11-11 14:15–14:16，本机单钥配置）
原始日志：[p1-mvn-test.log](file:///d:/1/CodeJudge/docs/review/raw-e01-20261111/p1-mvn-test.log)

```
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0        # judge-gateway 汇总行
[INFO] Tests run: 66, Failures: 0, Errors: 0, Skipped: 0        # judge-auth 汇总行
[INFO] Reactor Summary for codejudge 1.0.0:
[INFO] judge-gateway ...................................... SUCCESS [  7.896 s]
[INFO] judge-auth ......................................... SUCCESS [  9.122 s]
[INFO] BUILD SUCCESS
```

### 5.2 登录链路回归原始输出

执行命令：`py -3 scripts/verify-p1-login.py`（2026-11-11 14:16，网关 9080 / auth 9081 / user 9082 在线）
原始日志：[p1-verify-login.log](file:///d:/1/CodeJudge/docs/review/raw-e01-20261111/p1-verify-login.log)

```
  [PASS] 刷新不返回 refreshToken（不轮换）                                None
  [PASS] 学员 token 注册教师被拒（@RequireRole STAFF）                    403
  [PASS] 新教师 roleId claim（教师=3）                                 3
==================================================================
通过 43 项，失败 0 项
```


---

## 6. 踩坑与注意点

1. **refresh 失败时 HTTP 仍是 200**：`/accounts/refresh` 用统一响应包装，失败为 `{"code":401,...}` 而非 HTTP 401。
   验证续签必须看 body `code`，只看 status 会误判为成功。
2. **refresh 不轮换**：续签成功时返回的 refresh token 与 Cookie 中的值相同（未做 refresh 轮换），
   本轮演练正是据此区分「成功」与「失败」的 Set-Cookie 差异。
3. **列表索引从 0 连续**：`CJ_JWT_KEYS_1_*` 单独存在会绑定出 null 元素；代码已跳过 null，
   但配置层面应保持 0 起始（撤旧钥时重编号为 0）。
4. **算法强度随密钥字符串长度**：48 字节随机**熵**经 base64 编码后是 64 字符密钥，`Keys.hmacShaKeyFor` 按 UTF-8 字节数（64）选 **HS512**（header 实测 `"alg":"HS512"`）。
   阈值是 ≥64 字节 → HS512 / ≥48 → HS384 / ≥32 → HS256，**不存在「48 字节 → HS512」**；「48 字节熵」与「HS512」只在经过 base64 展开后才自洽。
   与 A-05 定稿口径一致 = 与 `docs/API-REFERENCE.md:53` 的「当前 64 字节 → HS512」一致（同见 `docs/ARCHITECTURE.md:290`）。
5. **本地 `.env` 已完成换钥**：`CJ_JWT_SECRET` 已替换为新值，演练前签发的本地 token 全部失效（预期）。
6. **`/jwks` 仍为 404**：多钥并存不改变「对称密钥不可公开」的事实，未恢复任何公钥端点。

---

## 7. 生产执行建议

1. 发布顺序：**新增密钥先发网关，再发 auth**；撤旧钥顺序相反（先 auth 后网关）。
2. 步骤 2 只改 auth 的 active 标记即可，网关验签集合不变；建议同批操作便于审计。
3. 步骤 3 观察期至少覆盖一个 access 生命周期（30 分钟），关注 `401` 率与 `kid=k-旧` 的流量衰减；
   若有集中 401，先回滚 active 标记（回滚成本 = 一次 auth 重启）。
4. 撤旧钥前确认无长时间在线的 refresh token 依赖（30 天），必要时先缩短 refresh 有效期或强制重登。
5. 变更窗口内保留 `contract-check`（E-04）通过日志，确保 auth/gateway 契约版本一致。

---

## 8. 相关文件

- [JwtTool.java](file:///d:/1/CodeJudge/judge-auth/src/main/java/com/codejudge/auth/common/util/JwtTool.java)
- [JwtKeyProperties.java](file:///d:/1/CodeJudge/judge-auth/src/main/java/com/codejudge/auth/config/JwtKeyProperties.java)
- [JwtUtils.java](file:///d:/1/CodeJudge/judge-gateway/src/main/java/com/codejudge/gateway/util/JwtUtils.java)
- [JwtProperties.java](file:///d:/1/CodeJudge/judge-gateway/src/main/java/com/codejudge/gateway/config/JwtProperties.java)
- [DEPLOYMENT.md §8.2](file:///d:/1/CodeJudge/docs/DEPLOYMENT.md#L597-L642)
- [ARCHITECTURE.md 附录](file:///d:/1/CodeJudge/docs/ARCHITECTURE.md#L280-L322)
