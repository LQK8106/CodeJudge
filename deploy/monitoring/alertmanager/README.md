# Alertmanager 告警外发接入

> 默认形态是 **`null` receiver —— 告警只出现在 Alertmanager UI（http://localhost:9093），
> 不会发到任何地方。** 上线前必须接一个真实通道，否则「有监控」等于「没有告警」。

本目录包含以下文件：

| 文件 | 作用 |
|---|---|
| `alertmanager.yml.tpl` | 配置**模板**，含 `${VAR}` 占位符 |
| `entrypoint.sh` | 容器入口：先把模板渲染成实际配置，再启动 alertmanager |
| `templates/codejudge-email.tmpl` | **中文告警邮件模板**：邮件主题、HTML 正文、纯文本正文 |
| `webhook-sink.py` | 本地 webhook 接收端：把收到的告警原始 JSON 打到 stdout，用于证明「告警发出去了」 |
| `README.md` | 本文 |

> **`null` 只允许 dev**：`ALERTMANAGER_ENV=prod` 时任一 RECEIVER 为 `null`，`entrypoint.sh` 会
> **拒绝启动**（fail-closed）；dev 只打印 `WARN`。目的是消灭「监控栈全绿、告警却没有出口」
> 这种最难发现的静默失效。

---

## 一、为什么多了一层 `entrypoint.sh`

因为 Alertmanager **做不到「凭据走环境变量」**，而这是本项目的硬规范。三个实测结论：

1. **`--config.expand-env` 不是 Alertmanager 的参数。**
   它是 **Prometheus** 的。在 `prom/alertmanager:v0.27.0` 上加这个 flag：
   ```
   alertmanager: error: unknown long flag '--config.expand-env'
   ```
   容器直接 CrashLoop。（如果不加，配置文件里的 `${VAR}` 会被当**字面量字符串**，
   receiver 名变成 `"${ALERTMANAGER_DEFAULT_RECEIVER}"`，路由永远匹配不到 →
   **告警静默不发，而 UI 和日志全都正常** —— 比崩溃更难发现。）

2. **官方镜像是精简镜像，没有 `envsubst`**（只有 `sh` / `sed` / `awk` / `grep`）。
   所以 `entrypoint.sh` 用 `awk` 做展开。它只替换 `${...}` 形式，
   **不会碰 Go 模板的 `{{ .Status }}`**（邮件主题里用到）。

3. **渲染结果有自检**：若残留 `${...}`（模板里变量名拼错），容器**直接退出**并打印位置，
   不带半成品配置启动 —— 避免又变成「启动成功但通知不到人」。

4. **告警出口有自检**：`ALERTMANAGER_ENV=prod` 且任一 RECEIVER 仍为 `null` / 空 →
   **拒绝启动**；dev 打印 `WARN`。`null` 在 Alertmanager 里是合法配置、UI 也一切正常，
   唯一表现就是「永远没人收到告警」，靠人工看配置极易漏掉。

---

## 二、启用步骤（以邮件为例）

### 1. 在项目根 `.env` 追加

```properties
# ---- 告警外发 ----
ALERTMANAGER_DEFAULT_RECEIVER=email
ALERTMANAGER_CRITICAL_RECEIVER=email
ALERTMANAGER_SMTP_SMARTHOST=smtp.example.com:587
ALERTMANAGER_SMTP_FROM=alertmanager@your-domain.com
ALERTMANAGER_SMTP_AUTH_USERNAME=alertmanager@your-domain.com
ALERTMANAGER_SMTP_AUTH_PASSWORD=<SMTP 授权码>
ALERTMANAGER_EMAIL_TO=oncall@your-domain.com
```

### 2. 带上 `--env-file` 重启

```bash
cd deploy/monitoring

# ⚠ --env-file 不能省。原因见第四节。
docker-compose --env-file ../../.env -f docker-compose.monitoring.yml \
    up -d --force-recreate alertmanager
```

### 3. 验证

```bash
docker logs codejudge-alertmanager | grep entrypoint
# [entrypoint] 默认接收器 = email ｜ critical = email

python scripts/preflight-check.py     # F1 应变为 PASS
```

### 4. 中文邮件模板

邮件主题与正文由 `templates/codejudge-email.tmpl` 渲染，**不再使用 Alertmanager 内置英文模板**：

- 主题形如：`【CodeJudge 告警】触发中｜紧急｜服务无响应 @ 判题机（judge-worker）｜共 1 条`
- 正文包含：告警概览（服务 / 级别 / 数量 / 通道）、每条告警的**中文名称、实例、触发时间（UTC）、摘要、排查详情**；
- 同时发送 HTML 与纯文本两个部分：HTML 用于阅读，纯文本兼容不支持 HTML 的客户端；
- 告警规则名、级别、状态、服务名均映射为中文，括号内保留原始标识，便于回查 `prometheus/rules/codejudge-alerts.yml`；
- 页脚「告警中心」链接取 `ALERTMANAGER_EXTERNAL_URL`，由 `entrypoint.sh` 以 `--web.external-url` 传给 Alertmanager（0.27 的配置文件**不支持** `external_url` 字段，写了会启动失败）。

> ⚠ 模板文件会同时被 `text/template` 与 `html/template` 解析；v0.27.0 只有
> `toUpper/toLower/title/trimSpace/join/match/safeHtml/reReplaceAll/stringSlice` 等函数，
> **没有** `date` / `tz` / `since` / `now`。改模板时时间只能用 `.StartsAt.Format "..."`
> （UTC），不要引入不存在的函数 —— 解析失败会导致**该 receiver 的通知全部发不出去**。

---

## 三、全部可配置变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ALERTMANAGER_DEFAULT_RECEIVER` | `null` | 默认路由与 warning 路由的接收器：`null` / `email` / `webhook` / `wecom` |
| `ALERTMANAGER_CRITICAL_RECEIVER` | `null` | critical 路由的接收器。**生产不可为 null** |
| `ALERTMANAGER_ENV` | `dev` | `dev` / `prod`。`prod` 下 RECEIVER 为 `null` 即拒绝启动 |
| `ALERTMANAGER_WEBHOOK_URL` | `http://alert-sink:8080/alerts` | 通用 webhook 接收端（默认本地 sink，生产填真实地址） |
| `ALERTMANAGER_WECOM_WEBHOOK` | `http://127.0.0.1:1/disabled` | 企业微信/钉钉 webhook（**需转换器**，见第五节） |
| `ALERTMANAGER_SMTP_SMARTHOST` | `localhost:25` | SMTP 服务器 `host:port` |
| `ALERTMANAGER_SMTP_FROM` | `alertmanager@codejudge.invalid` | 发件人 |
| `ALERTMANAGER_SMTP_AUTH_USERNAME` | 空 | SMTP 用户名 |
| `ALERTMANAGER_SMTP_AUTH_PASSWORD` | 空 | SMTP 密码 / 授权码 |
| `ALERTMANAGER_EMAIL_TO` | `alerts@codejudge.invalid` | 收件人 |
| `ALERTMANAGER_EXTERNAL_URL` | `http://localhost:9093` | 邮件正文「告警中心」链接地址（生产填对外可访问的域名） |

> 邮件相关默认值带 `.invalid` 后缀 —— 这是 RFC 2606 保留域名，**永远不会被解析到**，
> 保证「默认不外发」形态下配置依然合法（Alertmanager 会无条件校验所有 receiver，
> 哪怕它没被任何路由引用；`to` 为空会直接报 `missing to address in email config` 并 CrashLoop）。

---

## 四、⚠ 两个 Git Bash 环境陷阱（都实测踩过）

### 1. `VAR=x docker-compose ...` 前缀赋值**不生效**，`export` 也**不生效**

本机 `docker-compose` 是 Docker Desktop 的 v5.4.0 二进制（`/c/Program Files/Docker/Docker/resources/bin/docker-compose`）。
实测：无论用命令前缀赋值还是 `export`，它都**读不到 shell 环境变量**，一律走 compose 里的默认值。

```bash
# ❌ 不生效（容器内仍是 null）
ALERTMANAGER_DEFAULT_RECEIVER=email docker-compose ... up -d --force-recreate alertmanager
export ALERTMANAGER_DEFAULT_RECEIVER=email && docker-compose ... up -d --force-recreate alertmanager

# ✅ 生效
docker-compose --env-file ../../.env -f docker-compose.monitoring.yml up -d --force-recreate alertmanager
```

对比印证：同一 shell 里 `python -c "import os; print(os.environ['X'])"` **能**读到 `export` 的变量
（项目其它脚本如 `dev-start-backend.py` 依赖的正是这一点），只有这个 compose 二进制读不到。

### 2. 变量名大小写与作用域

`--env-file` 指向的必须是 **properties 格式**（`KEY=VALUE`，不要引号、等号两侧不要空格），
即项目根 `.env` 的格式 —— 与 Spring 读的是同一份，无需维护两套。

---

## 五、企业微信 / 钉钉：必须过一层转换器

**Alertmanager 原生 webhook 发的是自家 JSON**：

```json
{"version":"4","groupKey":"...","status":"firing","alerts":[...]}
```

而机器人要的是：

```json
{"msgtype":"markdown","markdown":{"content":"..."}}     // 企业微信
{"msgtype":"text","text":{"content":"..."}}             // 钉钉
```

**直接把机器人 URL 填进 `ALERTMANAGER_WECOM_WEBHOOK` 是收不到消息的**
（Alertmanager 认为发送成功，机器人那边静默丢弃 —— 又是一个「看起来配好了」的坑）。

两条路：

1. **不想引入中间件 → 用 `email`**。邮件是 Alertmanager 原生支持的，零依赖。
   对内部运维通知来说，邮件完全够用，建议优先选它。
2. **要发群消息 → 加一个转换器**，把 `ALERTMANAGER_WECOM_WEBHOOK` 指向转换器地址。
   社区现成镜像：`timonwong/prometheus-webhook-dingtalk`（钉钉）、
   各类 `webhook-bridge`（企业微信）。转换器不在本 compose 内，需自行部署。

---

## 六、验证告警真的能发出去（F1 验收）

> 只有「**接收端确实收到了**」才算通过。Alertmanager UI 里能看到告警、
> `preflight F1` 显示路由不是 `null`，都**不能**证明告警真的发到了人手里 ——
> 它们只证明「告警存在」和「配置看起来对」。

### 6.1 一条可直接勾选的验收步骤

```bash
# ① 确认生效接收器（预期：默认接收器 = webhook ｜ critical = webhook）
docker logs codejudge-alertmanager 2>&1 | grep entrypoint

# ② 注入一条 critical 测试告警
docker exec codejudge-alertmanager amtool alert add B05Preflight \
    severity=critical application=codejudge \
    --alertmanager.url=http://127.0.0.1:9093

# ③ 等 10~15s（critical 路由 group_wait=10s），看接收端日志
docker logs codejudge-alert-sink 2>&1 | tail -2
# 预期两行：
#   [alert-sink] POST /alerts bytes=... alerts=[('B05Preflight', 'firing', 'critical')]
#   [alert-sink] body={"receiver":"webhook",...,"status":"firing",...}

# ④ 交叉核对 Alertmanager 侧计数（预期：1，且 failed_total 全为 0）
docker exec codejudge-alertmanager wget -qO- http://127.0.0.1:9093/metrics \
  | grep 'alertmanager_notifications_total{integration="webhook"}'
```

**判据**：② 之后 15 秒内，③ 出现 `B05Preflight` 的接收记录 **且** ④ 的计数 ≥ 1。
任一不满足 = **F1 FAIL**（告警没有出口或出口不可达）。

清理测试告警（否则会一直躺在 UI 里）。⚠ v0.27 **没有** `DELETE /api/v2/alerts`（实测返回 405），
要用「同 labels + 过去的 `endsAt`」把它推成 resolved：

```bash
curl -X POST http://127.0.0.1:9093/api/v2/alerts \
  -H 'Content-Type: application/json' \
  -d '[{"labels":{"alertname":"B05Preflight","application":"codejudge","severity":"critical"},
        "endsAt":"2020-01-01T00:00:00Z"}]'
# 校验：curl -s http://127.0.0.1:9093/api/v2/alerts 应返回 []
```

### 6.2 换到生产通道后必须重跑

`ALERTMANAGER_WEBHOOK_URL` 改成真实接收端（或 `RECEIVER` 换成 `email` + 填 SMTP）后，
`--force-recreate alertmanager`，再走一遍 6.1 —— **改完不验证等于没改**。
真实接收端不会出现在 `alert-sink` 日志里，此时看 ④ 的计数 + 目标端（邮箱/群里）的实际消息。

### 6.3 周期演练（建议每周一次）

- 值班交接时跑一遍 6.1，记录「注入时间 / 接收端收到时间」；
- 连续 2 周失败按 **P1** 处理（等同于告警通道失效）；
- 变更过 `.env`、网络策略、SMTP 授权码或转换器后，当天必须重跑。

> `alert-sink` 只是本地验收用的「收件记录器」，**不是**生产通道：
> 它不发布端口、只打印日志，用来把「告警发出去了」这件事变成可观测的证据。

### 6.4 走 email 通道时怎么验（没有收件箱也能判到「SMTP 已接收」）

```bash
# ① 预期：默认接收器 = email ｜ critical = email
docker logs codejudge-alertmanager 2>&1 | grep entrypoint

# ② 注入一条 critical 测试告警
docker exec codejudge-alertmanager amtool alert add B06Email \
    severity=critical application=codejudge \
    --alertmanager.url=http://127.0.0.1:9093

# ③ 等 20~30s（group_wait 10s + SMTP 握手），看投递计数
docker exec codejudge-alertmanager wget -qO- http://127.0.0.1:9093/metrics \
  | grep -E 'alertmanager_notifications_total\{integration="email"\}|alertmanager_notifications_failed_total\{integration="email"\}'
```

**判据**：`alertmanager_notifications_total{integration="email"}` ≥ 1，且
**没有** `alertmanager_notifications_failed_total{integration="email"}` 序列。

为什么这能当证据：Alertmanager 只在 SMTP 服务器返回 **250（已接收）** 后才给成功计数 +1；
认证失败、连不上、收件人被拒都会进 `failed_total` 并在日志打 `Notify for alerts failed`。
**但「人真的看到了」仍要瞄一眼收件箱**——这一段容器无法代劳，属人工判据。

QQ 邮箱要点：`smtp.qq.com:587` + **授权码**（不是登录密码）；`smtp_require_tls: true`
（模板已固定）。授权码换过之后必须 `--force-recreate alertmanager` 才生效。
