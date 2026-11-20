# CodeJudge 测试报告

> 快照日期：2026-11-16
> 统计口径：本文只登记**仓库内可复算或可追溯到具体文件**的结果；每个关键数字后面括注来源。
> 本报告不引用简历、对话记录或未落盘的口头结论。

## 1. 结论摘要

| 维度 | 结论 | 关键证据 |
|---|---|---|
| 功能 | 后端 358 个单测、前端 167 个单测全部通过；功能回归 `verify-p6.py` 98 项通过 | §3 |
| 安全 | 4 个认证体系级漏洞（含密钥泄露、越权改密、SQL 盲注）已修复；鉴权契约 `verify-authz.py` 56 项全通过 | §3、[DEFECTS.md](./DEFECTS.md) |
| 性能 | 时延门槛 8/8 达标；恒定负载两轮 3 108.8 / 3 266.5 req/s、0 错误；1 小时长稳 8 879 545 样本、0 错误 | §4 |
| AI 质量 | 40 条分层样本，事实正确性 4.07 / 相关性 3.97 / 安全性 4.87 / 可执行性 2.98（满分 5） | §5 |
| 上线就绪 | 代码侧门禁通过；生产凭据、告警通道、HTTPS、判题容量决策 4 项未完成，**不可直接对外放行** | [RELEASE-GATE.md](./RELEASE-GATE.md) |

## 2. 被测对象与环境

**被测范围**：11 个 Maven 模块 = 8 个后端服务（网关、认证、用户、题目、提交、判题机、竞赛、AI）＋ 公共库 `judge-common`、契约库 `judge-api` ＋ 前端 `judge-web`（来源：`docs/ARCHITECTURE.md` §2）。

| 项 | 内容 | 来源 |
|---|---|---|
| 运行环境 | Windows 11（10.0.22631），AMD Ryzen 9 7940H（8C/16T），15 GB 内存 | `docs/PERF.md` §1 |
| 运行时 | JDK 21、Node 22、Docker（MySQL / Redis / PostgreSQL / RocketMQ / 监控栈均为容器） | `.github/workflows/ci.yml`、`docs/PERF.md` §1 |
| 压测工具 | JMeter 5.6.3，非 GUI + CSV 结果；压测客户端与被测服务同机 | `docs/PERF.md` §1、`perf-test/README.md` |
| 不在范围内 | 多机独立压测、生产网络与真实第三方回调、移动端 App、长时间（>1h）稳定性 | `docs/PERF.md` §1 红线说明 |

## 3. 用例与执行结果

| 层次 | 工具 / 命令 | 用例数 | 结果 | 来源 |
|---|---|---:|---|---|
| 后端单元测试 | `mvn -B -ntp verify`（Surefire） | 358 | 0 失败 | 各模块 `target/surefire-reports` 汇总（报告时间 2026-10-10 ~ 11-11） |
| 前端单元测试 | `npm test -- --run`（Vitest，10 个文件） | 167 | 167 通过 | 2026-11-16 实跑；另见 `docs/LAUNCH-CHECKLIST.md` B4 |
| 鉴权契约 | `scripts/verify-authz.py` | 56 | 56 / 0 | `docs/API-REFERENCE.md` 第 145 行 |
| 登录链路 | `scripts/verify-p1-login.py` | 43 | 43 / 0 | `docs/review/E-01-KEY-ROTATION-DRILL.md` |
| 判题链路 | `scripts/verify-p3.py` | 21 | 21 / 0 | `docs/LAUNCH-READINESS.md` §C |
| 功能回归 | `scripts/verify-p6.py` | 98 | 98 / 0 | `docs/LAUNCH-READINESS.md` §C |
| AI 点评链路 | `scripts/verify-p5.py` | 46 | 46 / 0 | `docs/LAUNCH-READINESS.md` §G |
| 三浏览器兼容 | `npx playwright test`（Chromium / Edge / Firefox × 5 条主流程） | 15 | 15 / 15，退出码 0 | `docs/compat/COMPAT-MATRIX-20261107.md` |
| 关键路径 E2E | `scripts/e2e-critical-path.cjs`（登录→选题→提交→判题 WS→AI 点评 SSE→榜单 WS） | 8 | 8 / 8 | `docs/LAUNCH-CHECKLIST.md` B4 |
| AI 效果评测 | `scripts/eval-ai-quality.py` | 40 样本 × 2 次 judge | 评测有效（退出码 0） | `docs/ai-eval/report-20261110.md` |
| 设计可测性审查 | 只读文档 + 实测对标 | 26 项 | 6 项高危已处置，20 项待办 | `docs/review/TESTABILITY-REVIEW-20261107.md` |

合计：**525 个自动化单测**（358 + 167）、**264 项端到端断言**（56 + 43 + 21 + 98 + 46）、**23 条浏览器用例**（15 + 8）。

> 前端覆盖率有一次历史读数：58.9% statements / 87.5% branches（136 个用例时，来源：`docs/LAUNCH-READINESS.md` §C）。该读数早于当前 167 用例，不能当作当前覆盖率。

## 4. 性能测试

### 4.1 结果与门槛

| 场景 | 负载 / 采样 | 实测 | 门槛 | 判定 | 来源 |
|---|---|---:|---|---|---|
| 混合负载 `load` | 5 890 请求 / 57.4s | 102.7 req/s，错误率 0.000%，各接口 P95 ≤ 60ms | 吞吐 ≥ 500 req/s | ❌ 不适用（见 4.2） | `docs/PERF.md` §3.4 |
| 工作量放大探针 | 16 290 请求 / 57.9s | 281.4 req/s，0 错误 | — | 证明吞吐受工作量限制 | `docs/PERF.md` §3.4 |
| 恒定负载 `throughput` 第 1 轮 | 559 035 样本 / 180s | 3 108.8 req/s，0 错误，登录 P95 139ms | ≥ 500 req/s，P95 < 300ms | ✅ 6.2× 余量 | `docs/PERF.md` §3.5 |
| 恒定负载 `throughput` 第 2 轮 | 587 311 样本 / 180s | 3 266.5 req/s（+5.1%），0 错误，登录 P95 146.1ms | 同上 | ✅ 可复现 | `docs/PERF.md` §3.5 |
| 1 小时长稳 `soak` | 8 879 545 样本 / 60min | 2 466.6 req/s，0 错误；P95：题目列表 15ms、题目详情 13ms、提交列表 10ms、榜单 11ms | 时延门槛 300–400ms | ✅ 余量 20–40× | `docs/PERF.md` §3.8、`docs/perf-evidence/2026-10-04-soak-1h/` |
| 判题吞吐（单实例） | 150 条提交 | 0.95 题/s，25/25 AC | 见 A4 决策 | ⚠ 容量受限 | `docs/PERF.md` §3.7 |
| 判题吞吐（3 实例） | 150 条提交 | 0.96 题/s，25/25 AC（限并发 4/实例） | 同上 | ⚠ 不随实例数增长 | `docs/PERF.md` §3.7 |

### 4.2 必须同时说明的口径

1. **`load` 计划的 500 req/s 判定无效**：该计划总工作量固定（约 5 890 请求），ramp-up 60s，观测吞吐的算术上限约 98 req/s；实测 102.7 req/s 已在上限附近，与服务水平无关（`docs/PERF.md` §3.4）。
2. **绝对吞吐只是下界**：压测客户端与被测服务同机，CPU / 内存 / 网卡互相争抢；结论用于相对对比与瓶颈定位，不作为生产容量承诺（`docs/PERF.md` §1）。
3. **多实例判题曾把正确解判成 TLE**：3 实例不限并发时 25 条里 18 条 TLE；限定每实例消费线程 4 后恢复 25/25 AC（`docs/PERF.md` §3.7）。这是已修复缺陷，不是当前行为。

## 5. AI 效果评测

| 维度 | 计入样本 | 均分（5 分制） | 判定方式 | 来源 |
|---|---:|---:|---|---|
| 事实正确性 | 30 | 4.07 | LLM judge | `docs/ai-eval/report-20261110.md` §2.1 |
| 可执行性 | 40 | 2.98 | 沙箱 `javac` 真实结果 | 同上 |
| 相关性 | 30 | 3.97 | LLM judge | 同上 |
| 安全性 | 30 | 4.87 | LLM judge | 同上 |

- 样本按 AC / WA / TLE / CE / RE 各 8 条分层，共 40 条，覆盖 9 道题（同报告 §1）。
- 10 条样本 judge 判定无效（`judgement_invalid`），已从 LLM 三维均分中排除，未做人工改分。
- 坏例 18 条，归为 3 类失败模式（同报告 §4）。
- **结论边界**：同一 judge、`temperature=0` 的两次调用同分只能证明“可复现”，不能证明与人类判断一致；本项目明确不引入人工金标准，不能对外表述为“AI 点评准确率 4.07/5”（同报告 §3）。

## 6. 缺陷

| 级别 | 数量 | 状态 |
|---|---:|---|
| 高危（P0–P1） | 7 | 全部修复 |
| 中危（P2） | 17 | 修复 16，暂缓 1（refresh token 轮换/吊销） |
| 低危（P3） | 10 | 全部修复 |
| 压测执行中发现 | 3 | 全部修复 |
| 配置 / 依赖整改 | 12 | 见 [DEFECTS.md](./DEFECTS.md) 附录 |

逐条的现象、根因、修复与验证见 [DEFECTS.md](./DEFECTS.md)；来源为 `docs/REVIEW-2026-09-25.md`、`docs/PERF.md`。

## 7. 遗留风险与不适用边界

1. **容量只有下界**：本机 3 108.8 req/s 不能代表生产；需独立压测机按 `perf-test/RUNBOOK.md` 重测（`docs/LAUNCH-CHECKLIST.md` B1）。
2. **判题容量未拍板**：0.95 题/s 且不随实例数增长，需在“接受现容量”与“减少每题容器数”之间决策（`docs/LAUNCH-CHECKLIST.md` A4）。
3. **refresh token 无轮换/吊销**：登出、改密、禁用后已签发 token 仍有效，属行为变更，待拍板（`docs/REVIEW-2026-09-25.md` §六）。
4. **可测性缺口未闭环**：26 项中 20 项（中 13 / 低 7）截至 2026-11-11 未逐项验证处置（`docs/review/TESTABILITY-REVIEW-20261107.md`）。
5. **生产动作未执行**：凭据轮换、告警通道、HTTPS、容量决策均需目标环境人工完成（`docs/LAUNCH-CHECKLIST.md` §一）。
6. **依赖扫描不阻断**：CI 的 `osv-scanner` 首期仅报告、不失败（`.github/workflows/ci.yml` 头部注释）。
7. **AI 评测无人类金标准**：10/40 判定无效、可执行性均分 2.98，不能当作“点评质量已达标”的证明（`docs/ai-eval/report-20261110.md`）。

## 8. 复现方式

```bash
# 后端单测 + 覆盖率门禁
mvn -B -ntp verify

# 前端单测 / 构建
cd judge-web && npm test -- --run && npm run build

# 端到端断言
python scripts/verify-authz.py
python scripts/verify-p1-login.py
python scripts/verify-p3.py
python scripts/verify-p6.py
python scripts/verify-p5.py

# 浏览器兼容矩阵 / 关键路径
npx playwright test
node scripts/e2e-critical-path.cjs
```

性能复现步骤、机器配置与陷阱说明见 `perf-test/README.md` 与 `docs/PERF.md` §5；原始压测结果归档在 `docs/perf-evidence/`。
