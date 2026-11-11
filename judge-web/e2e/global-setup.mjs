import { execFileSync } from 'node:child_process';
import fs from 'node:fs';

// 全局前置：创建「【E2E】兼容矩阵赛」并给三个学员报名，把竞赛 id 落到文件 +
// process.env，供各 project 的用例读取。这样 `npx playwright test` 一条命令即可跑完矩阵。
export default async function globalSetup() {
  const out = execFileSync(process.execPath, ['e2e/setup-contest-matrix.mjs'], {
    encoding: 'utf8',
    cwd: process.cwd(),
  });
  const m = out.match(/E2E_CONTEST_ID=(\d+)/);
  if (!m) throw new Error(`未从 setup-contest-matrix.mjs 取到竞赛 id：${out.slice(-300)}`);
  const info = { contestId: m[1], createdAt: new Date().toISOString() };
  fs.writeFileSync(new URL('./.contest-id.json', import.meta.url), JSON.stringify(info, null, 2));
  process.env.E2E_CONTEST_ID = m[1];
  console.log(`[global-setup] E2E_CONTEST_ID=${m[1]}`);
}
