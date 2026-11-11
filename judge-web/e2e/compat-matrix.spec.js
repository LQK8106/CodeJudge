// 三浏览器 × 五流程 兼容性矩阵用例（Chromium / Edge / Firefox）。
//
// 与 smoke.spec.js 的关系：冒烟用例是单浏览器（默认 project）跑一遍；
// 本文件是矩阵专用副本，差异只有三点：
//   1) 每个 project 绑定独立学员账号（chromium=学员一 / edge=学员二 / firefox=学员三），
//      这样同一竞赛榜上「过 N 题」不会在 project 之间叠加，榜单联动断言才是真实的；
//   2) 竞赛来自 setup-contest-matrix.mjs（三个学员均已报名），标题前缀为【E2E】兼容矩阵赛；
//   3) 断言口径完全一致：登录→题库→提交判题→AI 点评→榜单，串行执行、只登录一次。
//
// 运行（judge-web/ 目录内）：
//   npx playwright test        # global-setup 自动创建竞赛并报名三个学员
// 失败证据：test-results/compat-artifacts/**（screenshot/trace/video，retain-on-failure）
// 原始结果：../docs/compat/raw/playwright-<YYYYMMDD>.json（JSON reporter）

import fs from 'node:fs';
import { test, expect } from '@playwright/test';

test.describe.configure({ mode: 'serial' });

/** 竞赛 id：优先环境变量；否则读 global-setup 落盘的 e2e/.contest-id.json */
function resolveContestId() {
  if (process.env.E2E_CONTEST_ID) return process.env.E2E_CONTEST_ID;
  try {
    return JSON.parse(fs.readFileSync(new URL('./.contest-id.json', import.meta.url), 'utf8')).contestId || '';
  } catch {
    return '';
  }
}

/** project → 学员账号（与 setup-contest-matrix.mjs 的报名列表一一对应） */
const ACCOUNT_BY_PROJECT = {
  chromium: { phone: '13900000001', name: '演示学员一' },
  edge: { phone: '13900000002', name: '演示学员二' },
  firefox: { phone: '13900000003', name: '演示学员三' },
};

const PASSWORD = process.env.E2E_PASSWORD || '123456';
const CONTEST_ID = resolveContestId();

/** AC 解法必须用 long：4001 样例含 2147483647×2（int 溢出用例） */
const AC_JAVA = [
  'import java.util.*;',
  '',
  'public class Main {',
  '    public static void main(String[] args) {',
  '        Scanner sc = new Scanner(System.in);',
  '        long a = sc.nextLong();',
  '        long b = sc.nextLong();',
  '        System.out.println(a + b);',
  '    }',
  '}',
].join('\n');

/** 终态结论文案，口径同 utils/format.js 的 VERDICT 表 */
const TERMINAL_VERDICT = /通过|答案错误|超时|超内存|运行错误|编译错误|系统错误/;

let authState = null;   // T1 落下，后续断言复用（每个 project 独立 worker，互不干扰）
let submissionPath = null; // T3 产出，T4 复用

function account() {
  const name = test.info().project.name;
  return ACCOUNT_BY_PROJECT[name] || ACCOUNT_BY_PROJECT.chromium;
}

test('登录链路：手机号+密码 → 登录成功落地题库', async ({ page }) => {
  const acct = account();
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: '登录' })).toBeVisible();

  await page.getByPlaceholder('11 位手机号').fill(acct.phone);
  await page.getByPlaceholder('请输入密码').fill(PASSWORD);
  await page.getByRole('button', { name: '登录' }).click();

  await expect(page).toHaveURL(/\/problems/, { timeout: 15_000 });
  await expect(page.locator('.auth__card')).toHaveCount(0);

  authState = await page.context().storageState();
});

test('题库链路：题库列表可渲染且有题目数据', async ({ browser }) => {
  const ctx = await browser.newContext({ storageState: authState });
  const page = await ctx.newPage();

  await page.goto('/problems');
  await expect(page.locator('.cj-panel__table')).toBeVisible();
  const rows = page.locator('.cj-panel__table .el-table__row');
  await expect(rows.first()).toBeVisible();
  expect(await rows.count()).toBeGreaterThan(0);

  await ctx.close();
});

test('提交判题链路：竞赛选题→编辑→提交→轮询判题结果', async ({ browser }) => {
  test.setTimeout(120_000);
  if (!CONTEST_ID) {
    throw new Error('E2E_CONTEST_ID 未设置：先在 judge-web/ 内跑 node e2e/setup-contest-matrix.mjs');
  }

  const ctx = await browser.newContext({ storageState: authState });
  const page = await ctx.newPage();

  await page.goto(`/contests/${CONTEST_ID}`);
  await expect(page.locator('.cj-title', { hasText: '兼容矩阵赛' })).toBeVisible();
  await expect(page.getByText('已报名')).toBeVisible();

  await page.goto(`/problems/4001?contestId=${CONTEST_ID}`);
  // 只断言编辑器上的竞赛上下文提示：顶部导航里也有「竞赛提交记录」，全文本匹配会撞严格模式
  await expect(page.getByText(new RegExp(`竞赛提交（contestId=${CONTEST_ID}）`))).toBeVisible();
  await expect(page.locator('.editor__ta')).toBeVisible();

  await page.locator('.editor__ta').fill(AC_JAVA);
  await page.getByRole('button', { name: '提交判题' }).click();

  // 轮询到终态：WS 推送为主，「刷新状态」REST 兜底，不写死 sleep
  const verdictTag = page.locator('.prog-line .verdict');
  await expect(verdictTag).toBeVisible({ timeout: 15_000 });
  await expect(async () => {
    const current = (await verdictTag.innerText()).trim();
    if (!TERMINAL_VERDICT.test(current)) {
      const refresh = page.getByRole('button', { name: '刷新状态' });
      if (await refresh.isVisible().catch(() => false)) await refresh.click();
    }
    expect(TERMINAL_VERDICT.test((await verdictTag.innerText()).trim()),
      `等待判题终态，当前：${current}`).toBe(true);
  }).toPass({ timeout: 90_000, intervals: [2_000, 3_000] });
  await expect(verdictTag).toHaveText('通过');

  await page.getByRole('button', { name: '查看判题详情' }).click();
  await expect(page).toHaveURL(/\/submissions\/\d+/);
  submissionPath = new URL(page.url()).pathname;
  await expect(page.locator('.cj-card__head', { hasText: '用例结果' })).toBeVisible();
  await expect(page.locator('.cj-card__head .verdict').first()).toHaveText('通过');
  expect(await page.locator('.el-table__row').count()).toBeGreaterThanOrEqual(2);

  await ctx.close();
});

test('AI 点评链路：判题详情页生成点评并流式出正文', async ({ browser }) => {
  test.setTimeout(150_000);
  if (!submissionPath) throw new Error('前置提交链路未产出提交详情路径');

  const ctx = await browser.newContext({ storageState: authState });
  const page = await ctx.newPage();

  await page.goto(submissionPath);
  await expect(page.locator('.cj-card__head', { hasText: 'AI 代码点评' })).toBeVisible();
  await page.getByRole('button', { name: '生成 AI 点评' }).click();

  // 首帧正文（RAG 检索事件先于正文推送；LLM 首 token 需 5–20s）
  const aiBody = page.locator('.ai__body');
  await expect(aiBody).toBeVisible({ timeout: 60_000 });

  // 流式收尾：状态文案进入「生成完成」，正文非空
  await expect(page.locator('.ai__bar').getByText('生成完成')).toBeVisible({ timeout: 120_000 });
  expect((await aiBody.innerText()).trim().length).toBeGreaterThan(20);

  await ctx.close();
});

test('榜单链路：实时榜出现本 project 本次 AC 的参赛者', async ({ browser }) => {
  test.setTimeout(60_000);
  if (!CONTEST_ID) {
    throw new Error('E2E_CONTEST_ID 未设置：先在 judge-web/ 内跑 node e2e/setup-contest-matrix.mjs');
  }
  const acct = account();

  const ctx = await browser.newContext({ storageState: authState });
  const page = await ctx.newPage();

  await page.goto(`/contests/${CONTEST_ID}`);
  await expect(page.locator('.cj-card__head', { hasText: '排行榜' })).toBeVisible();

  const rankRows = page.locator('.el-table__row');
  await expect(rankRows.first()).toBeVisible({ timeout: 20_000 });

  // 每个学员在本场只 AC 一次 → 「过 1 题」是本次 project 的真实联动结果
  const myRow = page.locator('.el-table__row', { hasText: acct.name }).first();
  await expect(myRow).toBeVisible();
  await expect(myRow).toContainText('过 1 题');

  await ctx.close();
});
