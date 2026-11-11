// E2E 兼容性矩阵前置脚本：为三浏览器矩阵创建一场「进行中」的演示赛并报名三个学员。
//
// 与 setup-contest.mjs 的差异（为什么另建而不是改原脚本）：
//   1) 矩阵有 3 个 project，若共用同一账号，同一竞赛榜上「过 N 题」会叠加，
//      chromium 之后的 project 断言就会失败 → 这里为每个学员单独报名，
//      chromium=演示学员一 / edge=演示学员二 / firefox=演示学员三；
//   2) 不修改原脚本，保持既有 E2E 冒烟链路行为不变。
//
// 用法（在 judge-web/ 目录内，需先 `docker compose --profile app up -d` 全绿）：
//   node e2e/setup-contest-matrix.mjs
// 输出：
//   E2E_CONTEST_ID=<id>
//
// 登录预算：本脚本 4 次登录（教师 + 3 学员），矩阵每 project 再登录 1 次，
// 共 7 次，全部串行分布在整个跑测周期内，低于「2 req/s 突发 5」的限流。

const GW = process.env.E2E_GATEWAY || 'http://127.0.0.1:9080';

const TEACHER_PHONE = '13900000011'; // 演示教师一（sql/seed.sql）
const STUDENTS = [
  { phone: '13900000001', name: '演示学员一' }, // chromium
  { phone: '13900000002', name: '演示学员二' }, // edge
  { phone: '13900000003', name: '演示学员三' }, // firefox
];
const PASSWORD = '123456';
const PROBLEM_ID = 4001; // A + B Problem（题库种子，样例含 int 溢出用例）

/** 时间基准：宿主本地墙钟（CST）。服务端 8 个 Java 容器已统一 TZ=Asia/Shanghai。 */
function fmtLocal(d) {
  const p = (n) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}` +
    `T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  );
}

async function login(phone, password) {
  const r = await fetch(`${GW}/accounts/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cellPhone: phone, password }),
  }).then((x) => x.json());
  if (r.code !== 200 || !r.data?.accessToken) {
    throw new Error(`登录失败 ${phone}: ${JSON.stringify(r).slice(0, 200)}`);
  }
  return r.data.accessToken;
}

const teacher = await login(TEACHER_PHONE, PASSWORD);

const now = new Date();
const stamp = fmtLocal(now).slice(0, 16).replace('T', ' ');
const body = {
  title: `【E2E】兼容矩阵赛 ${stamp}`,
  description:
    'Playwright 三浏览器兼容性矩阵专用演示赛（进行中）：每 project 一个独立学员，' +
    '用于断言「提交→判题→实时榜」联动，可随时删除。',
  rule: 'ACM',
  startTime: fmtLocal(new Date(now.getTime() - 60_000)),
  endTime: fmtLocal(new Date(now.getTime() + 120 * 60_000)),
  freezeMinutes: 30,
  penaltyMinutes: 20,
  problems: [{ problemId: PROBLEM_ID, label: 'A', displayOrder: 0, fullScore: 100 }],
};

const created = await fetch(`${GW}/contests`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${teacher}` },
  body: JSON.stringify(body),
}).then((x) => x.json());
if (created.code !== 200 || !created.data?.id) {
  throw new Error(`创建竞赛失败: ${JSON.stringify(created).slice(0, 300)}`);
}
const contestId = String(created.data.id);

for (const s of STUDENTS) {
  const token = await login(s.phone, PASSWORD);
  const reg = await fetch(`${GW}/contests/${contestId}/register`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  }).then((x) => x.json());
  if (reg.code !== 200) {
    throw new Error(`报名失败 ${s.name}: ${JSON.stringify(reg).slice(0, 300)}`);
  }
  console.log(`  已报名：${s.name}（${s.phone}）`);
}

console.log(`E2E_CONTEST_ID=${contestId}`);
