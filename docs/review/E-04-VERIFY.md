# E-04 验证记录：token claim 契约版本启动期校验（四场景 + 对称反向演练）

- 日期：2026-11-11 14:25–14:27
- 环境：本机 Docker Desktop，`codejudge_default` 网络，镜像 `codejudge/judge-gateway:latest` / `codejudge/judge-auth:latest`（auth `token-claim-contract=v1`）
- 校验实现：`judge-gateway/.../contract/TokenContractCheckRunner.java`（`ApplicationRunner`，启动时读 `<auth>/actuator/info` 的 `app.token-claim-contract`）
- 原始缺口：两侧 claim 结构不同步时，症状只在用户侧表现为 401，发布窗口内无人发现

方法：不重启线上网关，用**一次性容器**（同一镜像 / 同网络 / `--env-file .env`）逐个场景覆写环境变量，观察退出码与日志。

---

## 场景总表

| # | 配置 | 对端 | 期望 | 实测 |
|---|---|---|---|---|
| 1 | `CJ_TOKEN_CONTRACT_VERSION=v2`（网关侧改） | judge-auth `v1` | 拒启 | **exit 1** ✅ |
| 2 | `CJ_TOKEN_CONTRACT_VERSION=v1`（默认） | 桩服务 `/actuator/info` 声明 `v2`（auth 侧改） | 拒启 | **exit 1** ✅ |
| 3 | `FAIL_UNREACHABLE=true` + `GW_AUTH_URI=http://127.0.0.1:1` | 不可达 | 拒启 | **exit 1**（重试 3 次后）✅ |
| 4 | `FAIL_UNREACHABLE=false` + 不可达 | 不可达 | WARN 放行 | **启动成功** ✅ |
| 5 | `CJ_CONTRACT_CHECK_ENABLED=false` | judge-auth `v1` | 跳过校验 | **启动成功** ✅ |

## 场景 1：只改网关侧（v2 vs v1）

```powershell
docker run --rm --name cj-gw-contract --network codejudge_default --env-file .env `
  -e GW_AUTH_URI=http://judge-auth:9081 ... -e CJ_TOKEN_CONTRACT_VERSION=v2 -e CJ_CONTRACT_CHECK_FAIL_UNREACHABLE=true `
  codejudge/judge-gateway:latest
```

```
2026-11-11 14:25:41.826 ERROR [main] [req=] c.c.g.contract.TokenContractCheckRunner : [contract-check] 拒绝启动：token claim 契约版本不一致
    · 本地(gateway)版本 : v2
    · 对端(auth)版本   : v1
    · 对端地址         : http://judge-auth:9081/actuator/info
    · 处理建议         : 先发布 judge-auth、确认 /actuator/info 已返回新版本，再发布 judge-gateway；
                         或将两侧 CJ_TOKEN_CONTRACT_VERSION 对齐为同一值。
Error starting ApplicationContext. To display the condition evaluation report re-run your application with 'debug' enabled.
EXIT_CODE=1
```

## 场景 2：只改 auth 侧（对称反向演练）

用一个桩 HTTP 服务冒充「已升级的 auth」，`/actuator/info` 返回 `{"app":{"token-claim-contract":"v2"}}`（`python:3.12-slim`，容器 `cj-contract-stub`，已清理）：

```powershell
docker run -d --name cj-contract-stub --network codejudge_default `
  -v "$env:TEMP\contract_stub.py:/app/stub.py:ro" python:3.12-slim python /app/stub.py
# 自检：{"app": {"token-claim-contract": "v2"}}

docker run --rm --name cj-gw-contract2 --network codejudge_default --env-file .env `
  -e GW_AUTH_URI=http://cj-contract-stub:9081 -e CJ_TOKEN_CONTRACT_VERSION=v1 codejudge/judge-gateway:latest
```

```
2026-11-11 14:26:08.361 ERROR [main] [req=] c.c.g.contract.TokenContractCheckRunner : [contract-check] 拒绝启动：token claim 契约版本不一致
    · 本地(gateway)版本 : v1
    · 对端(auth)版本   : v2
    · 对端地址         : http://cj-contract-stub:9081/actuator/info
    · 处理建议         : 先发布 judge-auth、确认 /actuator/info 已返回新版本，再发布 judge-gateway；
                         或将两侧 CJ_TOKEN_CONTRACT_VERSION 对齐为同一值。
EXIT_CODE=1
```

结论：**两侧任一改动都会被拦下**，本地/对端版本号在日志里可区分（`v2 vs v1` / `v1 vs v2`）。

## 场景 3：`failOnUnreachable=true` + auth 挂掉

```
2026-11-11 14:26:25.404 WARN  [main] [req=] c.c.g.contract.TokenContractCheckRunner : [contract-check] 第 1/3 次读取 http://127.0.0.1:1/actuator/info 失败（WebClientRequestException: finishConnect(..) failed with error(-111): Connection refused: /127.0.0.1:1），1000ms 后重试
2026-11-11 14:26:26.408 WARN  [main] [req=] ... 第 2/3 次读取 ... 1000ms 后重试
2026-11-11 14:26:27.413 ERROR [main] [req=] c.c.g.contract.TokenContractCheckRunner : [contract-check] 拒绝启动：无法读取 judge-auth 的契约版本
    · 本地(gateway)版本 : v1
    · 对端地址         : http://127.0.0.1:1/actuator/info
    · 失败原因         : WebClientRequestException: finishConnect(..) failed with error(-111): Connection refused: /127.0.0.1:1
    · 处理建议         : 确认 judge-auth 已启动且 /actuator/info 可达（management.info.env.enabled=true）；
                         本地无 auth 时置 CJ_CONTRACT_CHECK_FAIL_UNREACHABLE=false 放宽（生产必须为 true）。
EXIT_CODE=1
```

## 场景 4：`failOnUnreachable=false` + auth 挂掉（本地放宽）

```
2026-11-11 14:26:43.259 INFO  [main] [req=] c.codejudge.gateway.GatewayApplication : Started GatewayApplication in 3.675 seconds
2026-11-11 14:26:44.371 WARN  [main] [req=] c.c.g.contract.TokenContractCheckRunner : [contract-check] WARN 放行：无法读取 judge-auth 的契约版本（本地宽松模式） 本地=v1 url=http://127.0.0.1:1/actuator/info 原因=WebClientRequestException: finishConnect(..) failed with error(-111): Connection refused: /127.0.0.1:1
```

## 场景 5：`enabled=false`

```
2026-11-11 14:27:24.433 INFO  [main] [req=] c.codejudge.gateway.GatewayApplication : Started GatewayApplication in 6.549 seconds
2026-11-11 14:27:24.438 INFO  [main] [req=] c.c.g.contract.TokenContractCheckRunner : [contract-check] 跳过：cj.security.contract-check.enabled=false（本地/离线场景）
```

---

## 结论与边界

- 四类异常路径全部按设计动作：**版本不一致 → 拒启（不受宽松开关影响）**；不可达 → 严格拒启 / 宽松 WARN 放行；显式关闭 → 跳过并有日志。
- 校验是 `ApplicationRunner`，在 `Started GatewayApplication` **之后**执行；日志里能看到 Netty 已监听 9080 再退出。窗口极短且实例随后退出、不会被 LB 判为就绪，但如果编排以「端口可连」作为就绪判据，请改用 actuator health / 进程存活作为判据。
- 生产默认值、回滚命令已写入 `docs/DEPLOYMENT.md` §契约校验末尾（`ENABLED=true` / `FAIL_UNREACHABLE=true` / 两侧 `v1`；回滚 = 回退镜像 tag，且 auth+gateway 同退，禁止用 `enabled=false` 绕过）。

## 证据文件

- `docs/review/raw-e04-20261111/p5-gw-expect-v2.log`（场景 1）
- `docs/review/raw-e04-20261111/p5-gw-peer-v2.log`（场景 2）
- `docs/review/raw-e04-20261111/p5-gw-unreachable-strict.log`（场景 3）
- `docs/review/raw-e04-20261111/p5-gw-unreachable-lenient.log`（场景 4）
- `docs/review/raw-e04-20261111/p5-gw-disabled.log`（场景 5）

一次性容器 `cj-gw-contract*` / `cj-contract-stub` 已全部 `docker rm -f`，线上 `codejudge-judge-gateway-1` 未重启、未改动。
