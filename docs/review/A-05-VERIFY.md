# A-05 验证记录：三条安全断言（/jwks 404、篡改签名 401、登录限流 429）

- 日期：2026-11-11 14:29–14:34
- 环境：本机全栈（gateway `127.0.0.1:9080` → auth `:9081`），账号：种子学员账号（手机号 `13900000001`，密码为仓库内 seed 脚本中的测试口令）
- 测试性质：**只读 / 阴性测试**，未改密码、未删用户、未放宽限流阈值
- 定稿口径：`docs/API-REFERENCE.md:53`（HMAC 对称、64 字节密钥 → HS512、`/jwks` 已删除）；`docs/ARCHITECTURE.md:290`（算法强度复核）、`docs/ARCHITECTURE.md:342`（签名末位填充位说明）

---

## 断言① `GET /jwks` → 404

```
GET  /jwks  -> 404
GET  /jwks/ -> 404
```

对照（证明 404 不是网关整体不可用）：同一时刻 `POST /accounts/login`（正确口令）→ **HTTP 200**，`GET /accounts/me/capabilities`（有效 token）→ **200**。

## 断言② 篡改签名的 token → 401

取一枚有效 access token（`header.payload.signature`），**改第 3 段（签名）的中间字符**：

```
签名段长度=86，改动位置=第 44 个字符（共 86），原字符=j -> 新字符=A
GET /accounts/me/capabilities（篡改签名）-> 401
GET /accounts/me/capabilities（原 token 对照）-> 200
```

说明：按 `ARCHITECTURE.md:342`，HS512 签名 base64url 的**末位含填充位**，改末位可能仍是合法编码，故改中间位；同一枚 token 未篡改时 200，篡改后 401 —— 签名校验生效。

## 断言③ 登录限流覆盖 `/accounts/login` → 429

**关键事实（会影响断言写法）**：本系统登录失败**不以 HTTP 状态码表达**，而是 `HTTP 200 + body {"code":401,"msg":"用户名或密码错误"}`；
限流拒绝则是网关层真实 **HTTP 429**（body 为空）。

顺序单发 30 次错误口令：**全部 `HTTP 200 / body code=401`，未触发 429**。原因：每次登录耗时约 **2.1s**（BCrypt + DB），即 0.48 req/s，**低于令牌桶补充速率 2 req/s**，桶永远补满。

改为**并发 12 发**（同一 IP，同一 key）：

```
第 1 轮：并发 12，耗时 2.2s，分布 {(429, ''): 7, (200, 401): 5}
第 2 轮：并发 12，耗时 2.1s，分布 {(429, ''): 7, (200, 401): 5}
```

- 第一次 401（body code）：**第 1 次**（每个错误口令都返回）；
- 第一次 429（HTTP）：**并发场景下第 6 个起的请求**（令牌桶 `burst=5`，12 并发中 5 个放行、7 个 429，两轮一致）。

限流键（`judge-gateway/.../ratelimit/LoginRateLimitKeyResolver.java`）：

- key = `rate:login:{客户端IP}:{userId|anon}`，登录场景未认证，实际为 `rate:login:127.0.0.1:anon`；已带 `user-info` 头时按 IP+userId 拆分独立桶；
- 阈值（`judge-gateway/src/main/resources/application.yml` 的 `login-rate-limit` 路由）：`replenishRate=${GW_LOGIN_RATE_REPLENISH:2}`、`burstCapacity=${GW_LOGIN_RATE_BURST:5}`，运行容器实测为 **2 / 5**（未被压测放宽值覆盖）；
- 路由谓词同时覆盖 `/accounts/login`、`/accounts/admin/login` 及 `/v1/...` 别名，避免旧端点绕过。

## 限流恢复

令牌桶按 2 req/s 连续补充，`burst=5` 打空后约 **2.5s** 回满。实测静默 4s：

```
恢复后正确密码登录 -> http=200 body_code=200
```

## 附：执行中发现的文档/实现口径差异（不影响三条断言结论）

1. **登录失败用 body code 而非 HTTP 4xx**：任何按 HTTP 状态码写「登录失败 = 401」的测试都会误判（本次断言③第一版即如此）。建议在 `API-REFERENCE.md` 登录小节显式写明「业务失败 HTTP 恒 200，看 `code`」。
2. `/jwks` 的 404 来自网关路由缺失（无匹配路由），不是 auth 主动返回；与「端点已删除」结论一致，但语义上是「不存在」而非「已下线」。

## 结论

三条断言全部成立：**`/jwks` 404**、**篡改签名 401**（原 token 200 对照）、**登录限流在并发超过 burst=5 时返回 HTTP 429**，且限流窗口过后可正常登录（200）。限流对**顺序慢速爆破无效**（2.1s/次低于补充速率），仅对并发/高速尝试生效 —— 这一边界已如实记录。

## 证据文件

- `docs/review/raw-a05-20261111/a05-probe.log`（①/② 与顺序 30 次的原始输出片段）
- `docs/review/raw-a05-20261111/a05-ratelimit.log`（顺序 30 次：全 200/401，未触发 429）
- `docs/review/raw-a05-20261111/a05-burst.log`（并发 12：5 放行 / 7 个 429，两轮）
