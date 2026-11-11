# 质量补强三项任务 · 验收汇总（2026-11-07）

> 本文件汇总 A/B/C 三项任务的真实执行结果与验收状态，所有数字均可在对应子报告内找到出处，无补造。
> 执行环境：Windows 11 + Docker Desktop；`docker compose --profile app` 共 18 个容器，8 个业务服务（gateway/auth/user/problem/submission/worker/contest/ai）均 healthy，`judge-web` 在 `:5174`。

## 一、验收总表

### 任务 A：AI 点评质量全自动评测（8 条验收，7 条达标）

| # | 验收标准 | 结论 | 证据 |
|---|---|---|---|
| 1 | 一条命令跑通、退出码 0、同产 JSON 明细 + MD 报告 | 达标 | `py -3 scripts/eval-ai-quality.py --limit 40 --runs 2 --workers 2 --judge-model glm-4.5-flash --strategy diverse` → exit 0 |
| 2 | 样本 ≥30、verdict ≥4 类、全部 degraded=false | 达标 | 40 条（`ai_review.id` 4–211），AC/WA/TLE/CE/RE 各 8 条 |
| 3 | 4 维均有分数，无 null/占位 | 达标 | 事实 4.33 / 可执行 3.48 / 相关 4.4 / 安全 4.5 |
| 4 | 可执行性 100% 沙箱真实结果、日志可按 id 追溯 | 达标 | 39 个代码块 `javac` 实测，日志在 `raw/review-<id>.json` 的 `executability` |
| 5 | 一致率与加权 κ 写入报告 | 达标 | 一致率最低 100.0%，二次加权 κ 最低 1.0（阈值 0.6） |
| 6 | bad case 失败模式 ≥5 类，每类 1 条真实 id + judge 理由原文 | **未达标** | 实际 12 条、**4 类**（见 §七） |
| 7 | 报告含事实清单，数字可溯源 | 达标 | 报告 §6.1 共 9 行，逐条标出处章节 |
| 8 | 脚本无手工填分入口 | 达标 | 仅 `--limit/--verdicts/--judge-model/--dry-run/--strategy`，无分数入参 |

### 任务 B：三浏览器 × 五流程兼容性矩阵（全部达标）

| # | 验收标准 | 结论 | 证据 |
|---|---|---|---|
| 1 | `npx playwright test` 一条命令跑完三浏览器并出 JSON reporter | 达标 | exit 0，JSON 在 `docs/compat/raw/playwright-20261107.json` |
| 2 | Chromium 至少全绿，其余失败须归类 | 达标 | 三引擎均全绿，无失败可归类 |
| 3 | 覆盖流程 ≥5，每个浏览器同一组流程 | 达标 | 登录 / 题库 / 提交判题 / AI 点评 / 榜单，各 5 条 |
| 4 | 报告含 3×5 矩阵与通过比 | 达标 | **15/15（100%）**，5.1 分钟，无 SKIP、无 flaky |

### 任务 C：设计文档可测性审查（全部达标）

| # | 验收标准 | 结论 | 证据 |
|---|---|---|---|
| 1 | 检查项 ≥20、覆盖 5 类 | 达标 | 26 项：接口 6 / 异常降级 5 / 一致性 5 / 可观测 5 / 发布回滚 5 |
| 2 | 每条含检查项、结论、文档定位、建议 | 达标 | 报告 §1–§5 逐条 |
| 3 | 高/中/低分级且高级 ≥3 | 达标 | 高 6 / 中 13 / 低 7 |
| 4 | 无编造引用，区分文档缺失与设计缺失 | 达标 | 文档缺失 15 项、设计缺失 11 项 |

## 二、任务 A 关键数据

- **样本**：40 条，`ai_review.id` 4–211；verdict 各 8 条；review_type 1/2/3 = 33/4/3。
- **被测模型**：`deepseek-flash` 28、`glm-5.3-flash` 10、`glm-4.5-air` 2；judge 为 `glm-4.5-flash`（与被测均不同）。
- **抽样**：按 verdict 分层，层内按「模型 × review_type → 正文长度档 → 题目」覆盖（`--strategy diverse`，默认 `newest` 不变），未按分数或质量筛选。
- **judge 调用**：40 条 × 2 次 = 80 次评估（扩样本轮新增 18 条、36 次新调用；另 22 条复用同 rubric／同 judge／`temperature=0` 的历史结果），JSON 解析失败 0 次。
- **沙箱客观标签**：39 个代码块，L1 0 / L2 28 / 缺上下文 11 / 语法错误 0；11 条点评未给代码块。
- **坏例**：12 条、4 类。

| 归类 | 条数 | 代表样本 |
|---|---|---|
| `unverifiable_code` | 5 | review-126 |
| `truncation_repetition` | 3 | review-4 |
| `fact_contradiction` | 2 | review-206 |
| `generic_boilerplate` | 2 | review-100 |

## 三、任务 B 关键数据

- 一条命令、退出码 0，**15/15 通过**，总耗时 5.1 分钟，重试 0。
- 各浏览器 5/5；AI 点评链路 68.7–101.8 秒（真实 SSE 流式），其余链路 0.7–7.7 秒。
- 浏览器：Chromium 1243（Playwright 1.63.0）、系统 Edge 156.0.4314.58、Firefox 1543。
- 只改测试配置/用例，未改业务代码；失败自动保留 screenshot / trace / video。

## 四、任务 C 关键数据

- 26 项、5 类覆盖；分级 高 6 / 中 13 / 低 7。
- 6 项高危：JWT 算法与网关实现冲突、Alertmanager 告警出口为 null、`status=1` 但正文为空（实测 12 条）、requestId 跨 MQ 断链（`[req=]` 空值）、换密钥全站 401、发布顺序缺启动期校验。
- 区分文档缺失 15 项 / 设计缺失 11 项；全文引用均带文档路径与行号。

## 五、可直接对外的结论表述

1. 对 CodeJudge 的 AI 代码点评建立**全自动** LLM-as-Judge 评测：40 条真实点评语料、覆盖 5 类判题结论，4 维 rubric 均分事实 4.33 / 可执行 3.48 / 相关 4.4 / 安全 4.5；可执行性 100% 由沙箱 `javac` 编译 39 个代码块给出客观标签，双次一致率 100%、加权 κ 1.0；坏例 12 条归为 4 类，全程零人工打分。
2. 为 CodeJudge 搭建 Playwright 三浏览器（Chromium / Edge / Firefox）× 五主流程（登录、题库、提交判题、AI 点评、榜单）兼容性矩阵，一条 `npx playwright test` 跑完 **15 条用例、15/15 通过**，失败自动留存 screenshot/trace。
3. 对 6 份设计文档完成 26 项可测性审查，覆盖 5 类，识别 15 项文档缺失与 11 项设计缺失，其中 6 项高优先级缺口均给出文档定位与可执行断言建议。

## 六、未达标项与原因（任务 A 第 6 条）

要求 bad case 失败模式 ≥5 类，实际 **4 类**。已做两轮扩样（新增历史截断文本、服务兜底文本、`reviewType=3` 真实点评）后由 1 类提升到 4 类，但第 5 类在真实数据中不可达：

1. **`unsafe_content`**：对全量 158 条可用点评做安全硬标记扫描（系统提示词、绕过判题、读取隐藏用例、攻击沙箱、`rm -rf` 等），命中 **0 条**。
2. **`missing_actionable`**：现有 4 维 rubric 无「可操作性」维度，judge 仅在任一维度 ≤3 时才给失败模式；本批点评三维均为 4–5 分，故该标签结构上无法触发。

继续“凑满”只能改 rubric 维度、用正则把满分点评判成坏例或伪造正文，均违反提示词「4 维 rubric」与「不得编造/不得人工挑选」的约束，故未执行，报告 §4 已如实标注“未凑满 5 类”。

## 七、证据文件索引

| 任务 | 交付物 | 路径 |
|---|---|---|
| A | Rubric（v2，执行前定稿） | `docs/ai-eval/RUBRIC.md` |
| A | 评测脚本 | `scripts/eval-ai-quality.py` |
| A | 评测报告（固定 6 章 + 事实清单） | `docs/ai-eval/report-20261107.md` |
| A | 逐条 judge 原始 JSON（40 条 + 明细） | `docs/ai-eval/raw/`（41 个文件） |
| A | bad case 修复建议 | `docs/ai-eval/BAD-CASE-FIXES-20261107.md` |
| A | 语料补齐脚本 | `scripts/eval-corpus-seed.py` |
| B | 三 project 配置 | `judge-web/playwright.config.js` |
| B | 五流程矩阵用例 | `judge-web/e2e/compat-matrix.spec.js` |
| B | 全局夹具 | `judge-web/e2e/global-setup.mjs` |
| B | 矩阵报告 | `docs/compat/COMPAT-MATRIX-20261107.md` |
| B | JSON reporter 原始结果 | `docs/compat/raw/playwright-20261107.json` |
| C | 可测性审查报告 | `docs/review/TESTABILITY-REVIEW-20261107.md` |
