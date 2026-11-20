# CodeJudge × zx-learn 整改执行总结报告

> 报告编号：REMEDIATION-EXECUTION-SUMMARY-20261116
> 报告周期：2026-11-16
> 报告范围：CodeJudge、zx-learn 两个仓库的本轮整改执行结果
> 数据来源：`PROGRESS-CodeJudge-20261116.md`、`PROGRESS-zx-learn-20261116.md`、两仓库 `RELEASE-GATE.md`
> 报告人 / 审核人：整改执行（代码/配置/CI） / 待技术负责人复核

## 1. 一句话结论

> 本轮在两仓库共落地 **11 项代码/配置/CI 可执行整改**（CodeJudge 6 项、zx-learn 5 项 + 1 项进行中），
> 关闭了 3 类可闭合风险：判题容量决策与告警、教师自助注册入口、RAG 静默降级。
> **但两仓库当前均不可对外放行**：CodeJudge 门禁阻断行 **14/21**，zx-learn **20/32**；
> 剩余项几乎全部依赖生产凭据、告警接收端、HTTPS 证书、独立压测机、RAG 额度与人工标注等外部资源。

## 2. 范围与决策基线

### 2.1 执行边界

| 类别 | 处理方式 |
|---|---|
| 代码 / 配置 / 脚本 / CI 可落地项 | 本轮直接实施，并给出可复现的静态或构建验证 |
| 需要生产密钥、证书、告警接收端、压测机、RAG 额度、人工标注 | 一律标“阻塞”，**不伪造 PASS**，不写占位结论 |
| 涉及接口契约变更（手机号脱敏） | 仅完成影响面评估，不实施 |

### 2.2 已确认的决策基线

| 决策点 | 选定方案 | 影响 |
|---|---|---|
| CodeJudge 判题容量 | **方案 A：接受现容量** ≈0.95 题/s（单实例），3 实例 ≈0.96 题/s | 不做线程扩容；以两级积压告警 + SE 失败率告警兜底 |
| zx-learn 教师注册 | **方案 A：关闭自助注册**，教师由管理员创建 | 网关白名单、后端端点、前端入口三处同时移除 |
| zx-learn AI 方向 | **补齐能力**，不承诺效果 | 加启动探测、指标与生产 fail-fast；标注与额度仍阻塞 |
| 生产「AI 准确率」口径 | 未取得人类金标准前，**不得表述为“达标”** | `TEST-REPORT.md` §5 保持原口径未改 |

## 3. 两仓库完成情况总表

| 维度 | CodeJudge | zx-learn |
|---|---:|---:|
| 任务总数 | 48 | 55 |
| 已完成 | 6 | 5 |
| 进行中 | 0 | 1（Z3-3 运行时断言） |
| 阻塞 | 38 | 46 |
| 未开始 | 4 | 3 |
| 任务完成率 | 12.5% | 9.1% |
| P0 关闭率 | 3/22 | 4/37 |
| 门禁阻断行通过率 | **14/21** | **20/32** |
| 本期新增缺陷 | 0 | 0 |
| 本期回归失败 | 0 | 0 |
| 当前是否可放行 | **否** | **否** |

> 完成率口径：已完成 / 任务总数；阻塞与进行中均计入未完成。未开始项为准备类任务 C0-1~C0-3、Z0-1~Z0-3，以及 CodeJudge C7-3（未被选中的方案 B）。

## 4. 已完成改动清单

### 4.1 CodeJudge（6 项）

| 任务 | 改动内容 | 关键文件 |
|---|---|---|
| C4-1 | 判题容量决策：接受方案 A，写明单/多实例吞吐、扩容方向、复测触发条件 | `docs/PERF.md` §3.7 第 6 小节 |
| C4-2 | 固化容量约束公式 `实例数 × CJ_MQ_CONSUME_THREADS ≤ 宿主机 CPU 核数` | `docs/PERF.md` §3.7 |
| C4-3 | 告警规则 12 → 14 条：新增 `JudgeQueueBacklogCritical`（>600，5m）与 `JudgeSystemErrorRateHigh`（SE 占比 >1%，10m） | `deploy/monitoring/prometheus/rules/codejudge-alerts.yml` |
| C7-1 / C7-2 | 人工标注脚手架：分层导出 30 条样本（AC/WA/TLE/CE/RE 各 6）、二次加权 Cohen's κ 计算工具与标注规程 | `scripts/export-human-eval-samples.py`、`scripts/compute-human-kappa.py`、`docs/ai-eval/HUMAN-ANNOTATION.md` |
| C10-1 / C10-2 | 7 个服务的 springdoc 默认值由 `true` 收紧为 `false`，`.env.example` 显式 `CJ_SPRINGDOC_ENABLED=false` | `judge-{gateway,auth,user,problem,submission,contest,ai}/src/main/resources/application.yml`、`.env.example` |
| C11-1 / C11-2 / C11-3 | 容器日志 json-file `50m × 5` 覆盖 17/17 服务；新增宿主 logrotate 样例；同步部署检查单 | `docker-compose.yml`、`deploy/logrotate/codejudge`、`docs/LAUNCH-CHECKLIST.md` |

同时更新了 `docs/SLO.md`（明确 SLO 只覆盖时延与错误率、告警名与实际规则对齐）、`RELEASE-GATE.md`、`QUALITY-SUMMARY.md`、`tasks.md`。

### 4.2 zx-learn（5 项完成 + 1 项进行中）

| 任务 | 改动内容 | 关键文件 |
|---|---|---|
| Z3-1 | 决策：关闭教师自助注册，教师统一由管理员经 `POST /users` 创建 | 进度报告 §11、`RELEASE-GATE.md` §6 |
| Z3-2 | 三处同时移除：网关 `excludePaths` 删除 `/teachers/register`；`TeacherController` 删除 `@PostMapping("/register")` 与校验逻辑；前端删除 `registerTeacher`、角色单选组 | `JwtProperties.java`、`TeacherController.java`、`zx-web/src/api/auth.ts`、`RegisterView.vue` |
| Z3-5 | 文档同步：接口矩阵该行改为“已移除”，测试报告 §4 更新 | `docs/API-COVERAGE-MATRIX.md`、`docs/quality/TEST-REPORT.md` |
| Z4-2 | RAG 启动自检：新增 `EmbeddingAvailabilityProbe`（gauge `zx_rag_embedding_available`）、严格 `embedRemote` 不静默降级、生产 `ZX_LLM_RAG_FAIL_FAST=true` | `EmbeddingService.java`、`EmbeddingAvailabilityProbe.java`、`LlmProperties.java`、`application.yml`、`.env.example`、`docker-compose.prod.yml` |
| Z10-1 | CI 新增 `frontend` job（Node 20：`npm install` → `npm run typecheck` → `npm run build`） | `.github/workflows/ci.yml` |
| Z3-3（进行中） | 静态断言已通过（grep 零命中）；运行时匿名 401/404 断言待启动栈 | — |

## 5. 验证证据

| 验证项 | 方法 | 结果 |
|---|---|---|
| CodeJudge 告警规则 | `python -c "yaml.safe_load(...)"` | 14 条规则 YAML 解析通过 |
| CodeJudge 容量公式 | `grep -n "实例数 × CJ_MQ_CONSUME_THREADS" docs/PERF.md` | 命中 |
| CodeJudge 文档端点 | `grep -rn "CJ_SPRINGDOC_ENABLED:true" judge-*/src/main/resources/application.yml` | 无匹配 |
| CodeJudge 日志上限 | `docker compose --profile app config` + YAML 统计 | 17/17 服务均含 `max-size=50m`、`max-file=5` |
| CodeJudge 样本导出 | `python scripts/export-human-eval-samples.py --template` | 退出码 0，18 列表头 |
| CodeJudge κ 工具 | `python scripts/compute-human-kappa.py --selftest` | 退出码 0；加权 κ = 0.8865248227（内置数据） |
| zx-learn 注册入口 | `grep -rn "teachers/register\|registerTeacher" zx-gateway zx-user zx-web/src` | 零命中 |
| zx-learn RAG 指标 | `grep -rn "zx_rag_embedding_available" zx-aigc/src` | 命中 |
| zx-learn 前端类型检查 | `npm run typecheck` | exit 0 |
| zx-learn 前端构建 | `npm run build` | exit 0 |
| zx-learn CI 配置 | `grep -n "typecheck" .github/workflows/ci.yml` | 命中 `frontend` job |

**验证局限（已如实标注，未伪造通过）**：本机 `mvn` 不在 PATH、`promtool` 不存在、Java 服务与 Docker 运行栈未启动，因此编译、单测、promtool 校验、运行态 404、错误风暴磁盘上界、真实判题复测均未执行。zx-learn 后端同样未编译、未跑单测。

## 6. 阻塞项与责任方

| 阻塞项 | 涉及仓库 / 任务 | 需要谁提供 | 影响的结论 |
|---|---|---|---|
| 生产凭据生成与注入 | CodeJudge C1-*、zx-learn Z1/Z2 | 运维 / 财务 | D1、A9；B 组配置与密钥 |
| 告警接收端（邮件/webhook） | CodeJudge C2-* | 运维 | D2 |
| 域名与 TLS 证书 | CodeJudge C3-*、zx-learn D5 | 运维 | D3、全站 HTTPS |
| 运行栈可用窗口 | 两仓库 | 运维 | 运行态 404、日志上界、匿名注册断言、判题复测 |
| 第二台压测机 | CodeJudge C4-4/C4-5 | 测试 | B6/D4 转 PASS |
| `mvn` / 构建机 | CodeJudge C8/C9、zx-learn Z5/Z7 | 研发环境 | 编译与单测门禁 |
| `promtool` | CodeJudge C2-5 | 研发环境 | A10 转 PASS |
| RAG / Embedding 额度 | zx-learn Z4-1/Z4-3/Z4-6 | 采购或切换供应商 | RAG 主链路、AI 入口 |
| 2 名人工评审 + 足量样本 | CodeJudge C7-4 | 测试负责人 | AI 评测金标准 |
| DBA 清理无效教师数据 | zx-learn Z3-4/Z3-6 | DBA | 历史数据 |
| 允许推送整改分支 | zx-learn Z10-2 | 技术负责人 | CI 变红验证 |

## 7. 门禁变化

### 7.1 CodeJudge

| 门禁项 | 变化 |
|---|---|
| A10 告警规则语法 | ✅ PASS（12 条）→ ⚠ 有条件（14 条仅 YAML 校验，未跑 promtool） |
| B6 判题容量决策 | ❌ 未决策 → ⚠ 有条件（方案 A 已拍板，150 条复测未跑） |
| D4 判题容量拍板 | ❌ 未决策 → ⚠ 有条件（同 B6，待 C4-4 回填口径） |
| D5 接口文档生产关闭 | ⚪ 执行项 → ⚠ 待运行态验证（默认值已收紧） |
| D6 日志滚动 | ⚪ 执行项 → ⚪ 配置就绪（待实测磁盘上界） |
| D7 / D8 | D7 保持中间件回环绑定执行项；D8 依赖漏洞扫描为非阻断 |

结论：**阻断行通过 14/21，仍不得对外放行**。

### 7.2 zx-learn

| 门禁项 | 变化 |
|---|---|
| A11 工作区洁净度 | 备注更新为“12 个修改 + 新增文件未提交”；沿用 2026-09 构建结论 |
| §6 已知边界：教师注册 | “教师可自助注册” → **已关闭（代码层）**，运行时断言待验 |
| §6 已知边界：RAG | “RAG 不可用” → 有启动探测、指标与生产 fail-fast，**额度仍缺** |
| A / B / C / D / E 组 | 通过数不变：11/11、1/6、3/5、4/7、1/5 |

结论：**阻断行通过 20/32，仍不得放行**。

## 8. 行为变更与风险

| 编号 | 变更 | 风险 | 缓解措施 |
|---|---|---|---|
| BC-1 | CodeJudge 接口文档默认关闭 | 本地开发默认看不到 `/doc.html` | `.env.example` 与配置注释写明显式置 `CJ_SPRINGDOC_ENABLED=true` |
| BC-2 | zx-learn 教师自助注册入口消失 | 已有外部调用方会 401/404 | 接口矩阵已标注“已移除”；教师改由管理员创建 |
| BC-3 | zx-learn 生产启用 RAG fail-fast | 缺 key 时 zx-aigc 启动失败 | 部署顺序改为先注入 key；本地默认 false |
| BC-4 | CodeJudge 新增 SE 失败率与积压阈值 | 阈值是首版推导值，可能误报/漏报 | 已注释“M5 后重标”，暂未加 `@tune` 锚点 |
| BC-5 | zx-learn 手机号脱敏仅评估 | 全局掩码会破坏 `/users/me` 与后台回填 | 本期不改序列化，按场景掩码方案待产品决策 |
| BC-6 | 全部改动未提交 git | 干净 clone 取不到修复，可能发布旧行为 | 交付时随整改分支提交；两仓库均适用 |

## 9. 下一步（按依赖排序）

| 顺序 | 动作 | 出口条件 |
|---:|---|---|
| 1 | 运维提供凭据注入点与告警接收端 | preflight B4 PASS；测试告警 90s 内送达 |
| 2 | 提交整改分支并推送，验证 CI 会红（zx-learn Z10-2） | 故意类型错误时 `frontend` job 失败 |
| 3 | 启动运行栈，验证 CodeJudge 文档端点 404、日志上界、zx-learn 匿名注册 401/404 | 各断言可复现 |
| 4 | 安装 `promtool`、`mvn`，补跑规则校验与后端构建/单测 | A10、C8/C9 转 PASS |
| 5 | 配置域名与 TLS，完成全站 HTTPS | D3 无混合内容告警 |
| 6 | 申请第二台压测机，跑 150 条判题复测 | B6/D4 转 PASS，回填对外口径 |
| 7 | 补齐 Embedding 额度，跑 RAG 全链路 | 上传→切分→入库→检索语义命中 |
| 8 | 组织 2 名评审完成 30 条双人标注 | 加权 κ ≥ 0.6 后回填 `TEST-REPORT.md` §5 |

## 10. 证据索引

| 证据 | 位置 |
|---|---|
| CodeJudge 进度报告 | `d:\1\CodeJudge\docs\quality\PROGRESS-CodeJudge-20261116.md` |
| zx-learn 进度报告 | `D:\1\zx-learn\docs\quality\PROGRESS-zx-learn-20261116.md` |
| CodeJudge 门禁清单 | `d:\1\CodeJudge\docs\quality\RELEASE-GATE.md` |
| zx-learn 门禁清单 | `D:\1\zx-learn\docs\quality\RELEASE-GATE.md` |
| CodeJudge 实施规格 | `d:\1\CodeJudge\docs\quality\spec.md` |
| CodeJudge 任务清单 | `d:\1\CodeJudge\docs\quality\tasks.md` |
| 容量决策 | `d:\1\CodeJudge\docs\PERF.md` §3.7 第 6 小节 |
| 告警规则 | `d:\1\CodeJudge\deploy\monitoring\prometheus\rules\codejudge-alerts.yml` |
| 人工标注规程 | `d:\1\CodeJudge\docs\ai-eval\HUMAN-ANNOTATION.md` |

---

**总判定**：本轮把“能在仓库内闭合”的风险全部收口，且未用占位结论替换阻塞项；两仓库的放行结论仍为 **不可对外放行**，解除条件取决于 §9 中 1–8 项外部资源与复测的完成情况。
