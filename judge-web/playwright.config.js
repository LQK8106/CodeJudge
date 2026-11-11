import { defineConfig, devices } from '@playwright/test';

// 三浏览器 × 五流程 兼容性矩阵配置（原单 project 冒烟配置升级而来）。
//
// 覆盖流程：登录 → 题库列表 → 提交判题 → AI 点评 → 排行榜。
// 每个浏览器是独立 project（禁止在同一 project 内循环切换）：
//   chromium（Desktop Chrome） / edge（msedge channel） / firefox（Desktop Firefox）
// 前置：`docker compose --profile app up -d` 全绿；竞赛由 global-setup 自动创建并报名三个学员。
//
// 一条命令：
//   npx playwright test
//
// 证据：
//   ../docs/compat/raw/playwright-<YYYYMMDD>.json  —— JSON reporter 原始结果（按 project 分组）
//   test-results/compat-artifacts/**               —— 失败用例的 screenshot / trace / video（retain-on-failure）
//   test-results/compat-html/                      —— HTML 报告（失败现场可视化）
const d = new Date();
const pad = (n) => String(n).padStart(2, '0');
const RUN_DATE = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;

export default defineConfig({
  testDir: './e2e',
  testMatch: /compat-matrix\.spec\.js/,
  globalSetup: './e2e/global-setup.mjs',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ['list'],
    ['json', { outputFile: `../docs/compat/raw/playwright-${RUN_DATE}.json` }],
    ['html', { outputFolder: 'test-results/compat-html', open: 'never' }],
  ],
  outputDir: 'test-results/compat-artifacts',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:5174',
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'edge', use: { ...devices['Desktop Edge'], channel: 'msedge' } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
  ],
});
