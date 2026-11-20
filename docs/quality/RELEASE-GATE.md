# CodeJudge 上线质量门禁清单

> 快照日期：2026-11-16
> 判定原则：**所有“阻断”项必须为 PASS，才允许对外放行**；非阻断项不满足时需要书面记录风险承接人。
> 本清单只引用仓库内已有证据；每一项的“依据”列给出来源文件，便于复核。

## 1. 门禁总表

### A. 代码与测试（阻断）

| ID | 检查项 | 判定标准 | 实测 | 结论 | 依据 |
|---|---|---|---|---|---|
| A1 | 后端编译与单测 | `mvn -B -ntp verify` 编译成功、0 失败 | 358 用例 / 0 失败 | ✅ PASS | `.github/workflows/ci.yml`；各模块 `target/surefire-reports` |
| A2 | 覆盖率门禁 | 全局 LINE ≥ 0.10；`judge-contest` LINE ≥ 0.70 | 构建期 JaCoCo check 通过 | ✅ PASS | 根 `pom.xml` 插件段；`judge-contest/pom.xml` |
| A3 | 前端单测 | Vitest 0 失败 | 167 / 167，10 个测试文件 | ✅ PASS | 2026-11-16 实跑；`docs/LAUNCH-CHECKLIST.md` B4 |
| A4 | 前端生产构建 | `vite build` 退出码 0 | 类型检查 0 错误、构建 exit 0 | ✅ PASS | `docs/LAUNCH-CHECKLIST.md` §六 |
| A5 | 鉴权契约 | 56 项断言全通过（含前端静态断言） | 56 / 0 | ✅ PASS | `docs/API-REFERENCE.md` 第 145 行 |
| A6 | 登录链路 | 换钥/登录回归全通过 | 43 / 0 | ✅ PASS | `docs/review/E-01-KEY-ROTATION-DRILL.md` |
| A7 | 判题链路 | `verify-p3.py` 全通过，主路径时限 4.2s | 21 / 0 | ✅ PASS | `docs/LAUNCH-READINESS.md` §C |
| A8 | 功能回归 | `verify-p6.py` 全通过 | 98 / 0（22.7s） | ✅ PASS | `docs/LAUNCH-READINESS.md` §C |
| A9 | 硬编码 / 敏感文件 | `check-hardcoded-defaults.py` FAIL=0 | FAIL 0 / WARN 2 | ⚠ 有条件 | `docs/LAUNCH-READINESS.md` §C；2 项 WARN 属 A12 凭据确认项 |
| A10 | 告警规则语法 | `promtool check rules` 成功 | YAML 解析通过（现有 14 条）；本机无 `promtool`，2026-11-16 新增的 2 条未做 promtool 校验 | ⚠ 有条件 | `deploy/monitoring/prometheus/rules/codejudge-alerts.yml`；`python -c "yaml.safe_load(...)"` |
| A11 | 数据卷备份与恢复演练 | 恢复后行数一致 | MySQL 133=133、PostgreSQL 1=1 | ✅ PASS | `docs/LAUNCH-CHECKLIST.md` §六 |

### B. 性能与容量（阻断）

| ID | 检查项 | 判定标准 | 实测 | 结论 | 依据 |
|---|---|---|---|---|---|
| B1 | 接口时延 | 各接口 P95 < 300–400ms | 8/8 达标，最差 P95 139ms（登录） | ✅ PASS | `docs/PERF.md` §3.5 |
| B2 | 错误率 | 恒定负载错误率 < 1% | 0.000%（559 035 / 587 311 样本） | ✅ PASS | `docs/PERF.md` §3.5 |
| B3 | 吞吐 | `throughput` 计划 ≥ 500 req/s | 3 108.8 / 3 266.5 req/s（6.2× 余量） | ✅ PASS | `docs/PERF.md` §3.5 |
| B4 | 长稳 | 1 小时 0 错误、连接池无累积 | 8 879 545 样本、0 错误；active 归 0 | ✅ PASS | `docs/PERF.md` §3.8 |
| B5 | 判断口径正确性 | 不得用非容量模型判定吞吐 | `load` 计划 102.7 req/s 已判为不适用，并给出算术上限 | ✅ PASS（口径已纠正） | `docs/PERF.md` §3.4 |
| B6 | 判题容量决策 | 明确“接受现容量”或“排期优化” | 已决策：方案 A 接受 ≈0.95 题/s（`docs/PERF.md` §3.7 第 6 小节）；150 条判题复测未执行 | ⚠ 有条件（决策已记录，C4-4 待复测） | `docs/PERF.md` §3.7 |
| B7 | 独立压测机标定 | 第二台机器复测容量 | 未执行（本机数字仅下界） | ⚪ 非阻断 | `docs/LAUNCH-CHECKLIST.md` B1 |

### C. 兼容性与体验（非阻断）

| ID | 检查项 | 判定标准 | 实测 | 结论 | 依据 |
|---|---|---|---|---|---|
| C1 | 三浏览器兼容 | Chromium / Edge / Firefox 全绿 | 15 / 15，退出码 0，无重试 | ✅ PASS | `docs/compat/COMPAT-MATRIX-20261107.md` |
| C2 | 关键路径 E2E | 登录→提交→判题→AI→榜单全通 | 8 / 8 | ✅ PASS | `docs/LAUNCH-CHECKLIST.md` B4 |
| C3 | 沙箱隔离强度 | 运行时不与宿主共享内核 | 当前 runc + seccomp；gVisor 未部署 | ⚪ 非阻断（已知风险） | `docs/LAUNCH-CHECKLIST.md` B5 |
| C4 | AI 效果基线 | 四维均分记录在案，坏例归类 | 18 坏例 / 3 类；10 条 judge 无效 | ⚪ 非阻断（不得表述为“达标”） | `docs/ai-eval/report-20261110.md` |

### D. 生产化动作（阻断，必须由目标环境执行）

| ID | 检查项 | 判定标准 | 当前状态 | 结论 | 依据 |
|---|---|---|---|---|---|
| D1 | 生产凭据重新生成 | preflight B4 PASS；硬编码检查 WARN=0 | 未执行（本地 `.env` 旧值视为已泄露） | ❌ 未完成 | `docs/LAUNCH-CHECKLIST.md` A1 |
| D2 | 告警通道可送达 | 触发测试告警 90s 内收到；`null` receiver 拒绝启动 | `ALERTMANAGER_ENV=prod` 时 fail-closed 已验证；真实通道未配置 | ❌ 未完成 | `docs/LAUNCH-CHECKLIST.md` A2、`docs/review/B-05-VERIFY.md` |
| D3 | 全站 HTTPS | 无混合内容告警；SSE 逐字输出 | 未执行 | ❌ 未完成 | `docs/LAUNCH-CHECKLIST.md` A3 |
| D4 | 判题容量拍板 | 与 B6 二选一 | 已拍板方案 A（2026-11-16）；复测与对外口径回填待 C4-4 | ⚠ 有条件 | `docs/PERF.md` §3.7 第 6 小节 |
| D5 | 接口文档生产关闭 | `CJ_SPRINGDOC_ENABLED=false` 且 `CJ_DOC_WHITELIST_ENABLED=false` | 7 个服务默认值已改为 `${CJ_SPRINGDOC_ENABLED:false}`，`.env.example` 显式 false；运行态 404 未验 | ⚠ 待运行态验证 | `judge-*/src/main/resources/application.yml`；`.env.example` |
| D6 | 日志滚动 | 容器日志有大小上限 | `docker-compose.yml` 17/17 服务 json-file `50m × 5`；`deploy/logrotate/codejudge` 样例入库；错误风暴磁盘上界未实测 | ⚪ 配置就绪，待实测 | `docker compose --profile app config` 解析结果 |
| D7 | 中间件仅回环绑定 | 默认仅绑定回环地址 | 配置已就绪，需重建容器生效 | ⚪ 执行项 | `docs/LAUNCH-CHECKLIST.md` D1 |
| D8 | 依赖漏洞扫描 | 高风险清零后改为阻断 | 当前 `osv-scanner` 仅报告不阻断 | ⚪ 非阻断（首期） | `.github/workflows/ci.yml` 头部注释 |

## 2. 放行判定

| 条件 | 当前 |
|---|---|
| 代码 / 测试 / 性能类阻断项 | A1–A8、A11 与 B1–B5 **全部 PASS**；A9 有条件（2 项 WARN 属 D1 凭据确认项）；A10 新增 2 条规则仅 YAML 校验 |
| 生产化阻断项（D1–D4） | D1–D3 未完成；D4 已拍板（方案 A），复测与对外口径待 C4-4 |
| 非阻断风险 | 已记录：容量下界、gVisor 未启用、AI 评测无金标准、依赖扫描仅报告 |

**结论：代码质量达到可发布标准，但生产化条件未满足，当前不得对外放行。** 完成 D1–D4 并按 `docs/LAUNCH-CHECKLIST.md` §五 的命令序列复验后，才可勾选全部阻断项。
