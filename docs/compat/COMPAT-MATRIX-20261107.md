# 三浏览器 × 五流程 兼容性矩阵报告（2026-11-07）

> 结论：**15/15 全部通过**（Chromium / Edge / Firefox 各 5 条链路），一条命令 `npx playwright test` 跑完，退出码 0，总耗时 5.1 分钟，无失败、无跳过、无重试。
> 配置：[judge-web/playwright.config.js](../../judge-web/playwright.config.js)｜用例：[judge-web/e2e/compat-matrix.spec.js](../../judge-web/e2e/compat-matrix.spec.js)｜原始结果：[docs/compat/raw/playwright-20261107.json](./raw/playwright-20261107.json)

## 1. 本次结果概览

| 项目 | 值 |
|---|---|
| 运行时间 | 2026-11-07 16:12–16:17（CST） |
| 执行命令 | `npx playwright test`（工作目录 `judge-web/`，无需附加参数） |
| 退出码 | 0 |
| 用例总数 | 15（3 project × 5 流程），`workers=1` 串行 |
| 通过 / 失败 / 跳过 / flaky | **15 / 0 / 0 / 0** |
| 总耗时 | 5.1 分钟（其中 AI 点评链路占 4.4 分钟，为真实 LLM 流式耗时） |
| 重试 | 0（`retries: 0`，不以重试掩盖不稳定） |
| 被测环境 | 容器化全栈全绿：judge-gateway `:9080` + judge-web `:5174`（nginx 同源代理 `/api`、`/ws`）；judge-ai 等 8 服务 healthy |
| 测试夹具 | `e2e/global-setup.mjs` 自动创建竞赛（`【E2E】兼容矩阵赛`，contestId=2118979943874277377）并报名三个学员 |

**浏览器与引擎版本**

| project | 引擎 | 版本 | 说明 |
|---|---|---|---|
| `chromium` | Playwright bundled Chromium | 1243（Playwright 1.63.0） | 覆盖 Chrome 内核基线 |
| `edge` | 系统 Edge（`device: Desktop Edge` + `channel: 'msedge'`） | 156.0.4314.58 | 真实 Edge 安装，不是 Chromium 内核替代 |
| `firefox` | Playwright bundled Firefox | firefox-1543 | 覆盖 Gecko 内核 |

> 三个 project 各自独立 `browser` 上下文、独立 `storageState`、独立学员账号（学员一/二/三），cookie 不共享；登录限流（2 req/s 突发 5）通过「每个 project 只登录一次、后续链路复用登录态」规避。

## 2. 3×5 结果矩阵

| 流程（用例） | chromium | edge | firefox | 断言要点 |
|---|---|---|---|---|
| 登录链路 | **PASS** 3.4s | **PASS** 1.3s | **PASS** 2.2s | `/login` 表单 → 登录成功落到 `/problems`，登录卡片消失，`storageState` 落盘供后续复用 |
| 题库链路 | **PASS** 0.9s | **PASS** 0.9s | **PASS** 1.1s | 题库列表渲染且行数 > 0 |
| 提交判题链路 | **PASS** 7.7s | **PASS** 7.2s | **PASS** 7.3s | 竞赛选题 → 编辑器写入 AC 代码 → 提交 → 轮询终态 → 判定「通过」→ 判题详情用例行数 ≥ 2 |
| AI 点评链路 | **PASS** 101.8s | **PASS** 68.7s | **PASS** 93.6s | 详情页点「生成 AI 点评」→ 首帧正文出现 → 生成完成且正文 > 20 字符（真实 SSE 流式，非 mock） |
| 榜单链路 | **PASS** 0.7s | **PASS** 0.8s | **PASS** 1.1s | 竞赛实时榜出现本 project 学员行，且显示「过 1 题」（与本次 AC 真实联动） |
| **小计** | **5/5** | **5/5** | **5/5** | 合计 **15/15（100%）** |

> 耗时取自 JSON reporter 的 `duration` 字段（毫秒→秒，保留一位小数），与 [raw/playwright-20261107.json](./raw/playwright-20261107.json) 一致。

## 3. 失败归类

本次 **0 失败、0 跳过、0 flaky**，因此不存在「产品缺陷 / 环境限制」归类条目。

失败证据机制已就位（供后续回归使用）：`use.screenshot='only-on-failure'`、`trace='retain-on-failure'`、`video='retain-on-failure'`，产物落在 `judge-web/test-results/compat-artifacts/`；HTML 报告在 `judge-web/test-results/compat-html/`。

## 4. 环境与限制说明

- **AI 点评链路耗时远高于其他流程**（68–102 秒）：这是 `judge-ai` 真实调用大模型 + SSE 流式返回的固有耗时，不是浏览器差异；因此整体用时 5.1 分钟，其中 4.4 分钟耗在该链路。三个浏览器均未超时（用例超时 30s 仅约束页面操作，点评断言单独放宽）。
- **只改测试配置与测试文件**：本次变更仅 `judge-web/playwright.config.js`、`judge-web/e2e/compat-matrix.spec.js`、`judge-web/e2e/global-setup.mjs` 与 `package.json` 的测试脚本，未触碰任何业务代码。
- **`playwright.compat.config.js` 保留为转发入口**：三浏览器矩阵已并入 `playwright.config.js`，该文件 `export { default } from './playwright.config.js'`，避免旧引用失效。
- **浏览器依赖齐全，无 SKIP**：Chromium、系统 Edge、Firefox 三个引擎均可启动并完成全部 5 条链路，故本报告不含 SKIP 项。

## 5. 证据文件

| 类型 | 路径 |
|---|---|
| 原始 JSON（JSON reporter） | `docs/compat/raw/playwright-20261107.json` |
| 配置 | `judge-web/playwright.config.js` |
| 用例 | `judge-web/e2e/compat-matrix.spec.js` |
| 全局夹具 | `judge-web/e2e/global-setup.mjs`、`judge-web/e2e/setup-contest-matrix.mjs` |
| 失败产物目录（本次为空） | `judge-web/test-results/compat-artifacts/` |
| HTML 报告 | `judge-web/test-results/compat-html/index.html` |

## 6. 可直接对外的结论表述（≤2 句）

1. 为 CodeJudge 搭建 Playwright 三浏览器（Chromium / Edge / Firefox）× 五主流程（登录、题库、提交判题、AI 点评、榜单）兼容性矩阵，一条 `npx playwright test` 跑完 **15 条用例、15/15 通过**，退出码 0。
2. 用例串行复用登录态以规避登录限流，失败自动保留 screenshot/trace/video，矩阵结果与原始 JSON 一并归档可复查。
