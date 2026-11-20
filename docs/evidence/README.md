# CodeJudge 质量证据包

分布式在线编程评测平台（11 个 Maven 模块 = 8 个后端服务 + 公共库 / 契约库 + 前端）的质量工作留痕。

**本目录只做导航，不复制数据。** 所有数字只有一个来源，就在下方链接指向的原始文档里；若本页与原始文档不一致，以原始文档为准。

---

## 一分钟结论

**代码质量达到可发布候选（RC）水平，生产就绪度不足，当前不具备对外放行条件。**

- 综合质量评级 **B（有条件通过）**，上线就绪 **D（阻断）**
- 30 项上线门禁中，21 项为阻断项，通过 14 项
- 未关闭的 4 项生产阻断：**生产凭据 / 告警通道 / HTTPS / 判题容量决策**

| 维度 | 评级 | 结论 | 证据 |
|---|---|---|---|
| 功能正确性 | A | 525 个单测、264 项端到端断言、23 条浏览器用例全部通过 | [TEST-REPORT.md](../quality/TEST-REPORT.md) §3 |
| 安全性（代码侧） | B | 4 个认证体系级漏洞与 IDOR、SQL 注入已修复并有独立回归；refresh token 无轮换、HTTPS 未落地 | [DEFECTS.md](../quality/DEFECTS.md) §7 |
| 性能与容量 | B | 时延门槛 8/8 达标，恒定负载 3 108.8 / 3 266.5 req/s；但仅同机下界，判题容量 0.95 题/s 且不随实例线性增长 | [PERF.md](../PERF.md) §3 |
| 稳定性 | A | 1 小时长稳 8 879 545 样本、0 错误，连接池无累积 | [PERF.md](../PERF.md) §3.8 |
| AI 质量 | C | 40 条分层样本四维评测，可执行性 2.98 为最弱维度，18 条坏例归 3 类；无人类金标准 | [ai-eval/](../ai-eval/) |
| 工程化 / CI | B | 覆盖率门禁与前端构建已进 CI；SpotBugs 与依赖扫描仍「仅报告、不阻断」 | `.github/workflows/ci.yml` |
| 可观测性 | B | 9 个看板、14 条告警规则；告警通道尚未配置 | [RELEASE-GATE.md](../quality/RELEASE-GATE.md) A10/D2 |

完整评级口径与风险清单见 [QUALITY-SUMMARY.md](../quality/QUALITY-SUMMARY.md)。

---

## 口径声明（先读这段，再看数字）

1. **所有容量数字是同机下界**，压测客户端与被测服务同机运行，CPU / 内存 / 网卡互相争抢，不作为生产容量承诺。
2. **AI 评测是内部回归基线**，无人类金标准；同一 judge 两次调用同分只能证明「可复现」，不能证明与人类判断一致，**不得表述为「准确率」**。
3. **本项目未上线**。上述结论全部来自上线前预演，不代表生产环境已验证。
4. **不可挂靠邻仓数据**：本仓库的多模块底座（公共库 / 契约库 / 网关鉴权 / 监控栈）由 zx-learn 复用改造而来，但两仓库的质量数据各自独立实测，**不可互相套用**。

---

## 怎么读

| 你想知道 | 去哪里 |
|---|---|
| 结论、评级、放行建议、风险排序 | [QUALITY-SUMMARY.md](../quality/QUALITY-SUMMARY.md)（84 行，建议先看这个） |
| 每个数字的实测值与来源 | [TEST-REPORT.md](../quality/TEST-REPORT.md)（125 行，逐行括注出处） |
| 测试怎么设计的：范围、方法、取舍、局限 | [01-approach.md](01-approach.md)（120 行，不含数字，只讲方法） |
| 30 项门禁逐项判定 | [RELEASE-GATE.md](../quality/RELEASE-GATE.md) |
| 每个缺陷的现象 / 根因 / 修复 / 验证 | [DEFECTS.md](../quality/DEFECTS.md) |
| 性能测试方法与陷阱 | [PERF.md](../PERF.md) §3–§5 + `perf-test/README.md` |
| AI 评测评分标准与坏例 | [ai-eval/RUBRIC.md](../ai-eval/RUBRIC.md)、[report-20261110.md](../ai-eval/report-20261110.md) |
| 三浏览器兼容矩阵 | [compat/COMPAT-MATRIX-20261107.md](../compat/COMPAT-MATRIX-20261107.md) |
| 设计可测性评审 | [review/TESTABILITY-REVIEW-20261107.md](../review/TESTABILITY-REVIEW-20261107.md) |

---

## 自己跑一遍

```bash
# 后端单测 + 覆盖率门禁
mvn -B -ntp verify

# 前端单测 / 构建
cd judge-web && npm test -- --run && npm run build

# 端到端断言（鉴权 / 登录 / 判题 / 功能回归 / AI 点评）
python scripts/verify-authz.py && python scripts/verify-p1-login.py
python scripts/verify-p3.py && python scripts/verify-p6.py && python scripts/verify-p5.py
```

预期结果、环境要求与已知陷阱见 [TEST-REPORT.md](../quality/TEST-REPORT.md) §8 与 [PERF.md](../PERF.md) §5。

---

## 关于本目录

- `docs/quality/` 同时包含**整改期工作文档**（`REMEDIATION-PLAN.md`、`EXECUTION-CHECKLIST.md`、`PROGRESS-*`）。这些文件记录的是过程中的中间状态，**结论性内容以本 README 与 `QUALITY-SUMMARY.md` 为准**。
- 本目录不含任何凭据、内网地址或个人联系信息；本项目的运行验证均在本机隔离环境完成。
