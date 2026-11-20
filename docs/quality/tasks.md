# 整改实施任务清单（Tasks）

> 对应规格：`docs/quality/spec.md`
> 状态：`[ ]` 未开始 · `[-]` 进行中 · `[x]` 已完成（有命令证据） · `[!]` 阻塞
> 规则：不写工期；未真实执行验收命令的项不得标 `[x]`。
> 执行日期：2026-11-16。证据汇总见 `PROGRESS-CodeJudge-20261116.md`、`PROGRESS-zx-learn-20261116.md`。

## A. CodeJudge

### A1 C4 容量决策与告警

- [x] **T-C4-1** `docs/PERF.md` §3.7 追加“容量决策（2026-11-16，方案 A）”
  - 证据：`grep -n "容量决策（2026-11-16" docs/PERF.md` 命中
- [x] **T-C4-2** `docs/SLO.md` 补充“不含吞吐承诺”口径
  - 证据：§1 告警映射与口径边界已更新
- [x] **T-C4-3** `codejudge-alerts.yml` 新增 `JudgeSystemErrorRateHigh`（SE 占比 >1%，10m）
  - 证据：`grep` 命中；YAML 解析通过
- [x] **T-C4-4** `codejudge-alerts.yml` 新增 `JudgeQueueBacklogCritical`（>600，5m）
  - 证据：`grep` 命中；YAML 解析通过
- [!] **T-C4-5** `promtool check rules` 校验
  - 阻塞：本机 `promtool` 不存在；仅完成 YAML 解析
- [!] **T-C4-6** 150 条判题复测（25/25 AC）
  - 阻塞：需运行栈与压测前置

### A2 C7 人工标注脚手架

- [x] **T-C7-1** 新增 `scripts/export-human-eval-samples.py`
  - 证据：`--template` 退出码 0，18 列表头
- [x] **T-C7-2** 新增 `scripts/compute-human-kappa.py`
  - 证据：`--selftest` 退出码 0，κ=0.8865248227
- [x] **T-C7-3** 新增 `docs/ai-eval/HUMAN-ANNOTATION.md`
  - 证据：已写明“实际标注未执行”
- [!] **T-C7-4** 30 条双人标注并产出 κ
  - 阻塞：需 2 名评审 + 数据库样本

### A3 C10 文档端点默认关闭

- [x] **T-C10-1** 7 个服务 `application.yml` 默认值改为 `false`
  - 证据：`grep -rn "CJ_SPRINGDOC_ENABLED:true"` 无匹配
- [x] **T-C10-2** `.env.example` 增加显式 `CJ_SPRINGDOC_ENABLED=false`
  - 证据：已添加并附说明
- [x] **T-C10-3** 更新 `docs/LAUNCH-CHECKLIST.md` D5 说明
  - 证据：D5 行已改为“默认关闭”
- [!] **T-C10-4** 运行态 404 验证
  - 阻塞：需启动服务（本机无 mvn）

### A4 C11 日志轮转

- [x] **T-C11-1** `docker-compose.yml` 增加 `x-logging` 并挂到全部服务
  - 证据：`docker compose --profile app config` 解析后 17/17 服务含 `50m × 5`
- [x] **T-C11-2** 新增 `deploy/logrotate/codejudge`
  - 证据：含 `rotate 7`、`size 50M`、`copytruncate`
- [x] **T-C11-3** 更新 `docs/LAUNCH-CHECKLIST.md` D6
  - 证据：D6 行已更新
- [!] **T-C11-4** 错误风暴磁盘上界实测
  - 阻塞：需运行栈

## B. zx-learn

### B1 Z3 关闭教师自助注册

- [x] **T-Z3-1** 网关白名单删除 `/teachers/register`
  - 证据：`grep -rn "teachers/register" zx-gateway` 无匹配
- [x] **T-Z3-2** `TeacherController` 删除注册端点与校验方法
  - 证据：`grep -n "PostMapping(\"/register\")"` 无匹配
- [x] **T-Z3-3** `zx-web` 删除 `registerTeacher` 与教师注册入口
  - 证据：`grep -rn "registerTeacher\|teachers/register" zx-web/src` 无匹配
- [x] **T-Z3-4** 文档引用更新
  - 证据：现行 `API-COVERAGE-MATRIX.md`、`TEST-REPORT.md` 已更新；两份带日期的历史报告（GO-LIVE-AUDIT / FULL-TEST-PLAN）保留原文不改写
- [x] **T-Z3-5** 前端类型检查与构建
  - 证据：`npm run typecheck`、`npm run build` 均 exit 0
- [!] **T-Z3-6** 后端编译/单测与匿名 403 运行态断言
  - 阻塞：本机无 `mvn`，且未启动栈

### B2 Z10 前端 CI

- [x] **T-Z10-1** `.github/workflows/ci.yml` 增加 `frontend` job
  - 证据：`grep -n "typecheck" .github/workflows/ci.yml` 命中
- [x] **T-Z10-2** 本地 typecheck/build 复验
  - 证据：均 exit 0
- [!] **T-Z10-3** 故意制造类型错误验证 CI 变红
  - 阻塞：需推送触发 CI

### B3 Z4 RAG 可用性探测

- [x] **T-Z4-1** `LlmProperties` 增加 `ragFailFast` / `embeddingProbeOnStartup`
  - 证据：grep 命中两个字段
- [x] **T-Z4-2** 新增 `EmbeddingAvailabilityProbe`
  - 证据：grep 命中 `zx_rag_embedding_available`
- [x] **T-Z4-3** `application.yml` 与 `.env.example`/prod compose 配置
  - 证据：三处均命中 `rag-fail-fast` / `ZX_LLM_RAG_FAIL_FAST`
- [!] **T-Z4-4** 启动探测与真实检索验证
  - 阻塞：需 mvn + Embedding 额度

### B4 Z11 手机号脱敏

- [x] **T-Z11-1** 输出消费方评估结论到进度报告
  - 证据：`PROGRESS-zx-learn-20261116.md` §8
- [!] **T-Z11-2** 实施脱敏与审计
  - 阻塞：接口契约变更，待产品/后端决策

## C. 报告与回填

- [x] **T-R1** 新建 `docs/quality/PROGRESS-CodeJudge-20261116.md`
- [x] **T-R2** 新建 `D:\1\zx-learn\docs\quality\PROGRESS-zx-learn-20261116.md`
- [x] **T-R3** 回填 CodeJudge `RELEASE-GATE.md` 中有命令证据的项（A10/B6/D4/D5/D6）
- [x] **T-R4** 回填 zx-learn `RELEASE-GATE.md` 中有命令证据的项（A11、§6）
- [x] **T-R5** 复核：报告无编造数字、无密钥、无内网地址；仅规格中显式列出的工作区路径
