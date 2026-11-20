# 整改实施规格（Spec）

> 适用范围：CodeJudge（本仓库）与 zx-learn（`D:\1\zx-learn`）的**代码可落地整改项**。
> 依据：两仓库 `docs/quality/REMEDIATION-PLAN.md`、`EXECUTION-CHECKLIST.md`。
> 决策基线：2026-11-16 已确认——判题容量采用方案 A（接受现容量）；zx-learn 教师注册采用方案 A（关闭自助注册）；AI 方向为“补齐能力”，但人工标注与 RAG 额度属外部资源，**不得伪造 PASS**。
> 原则：只改代码/配置/文档可验证的部分；需要生产密钥、证书、告警接收端、外部额度、第二台压测机的任务一律标阻塞。

## 1. 范围与不做事項

### 1.1 本次实施

| 仓库 | 任务 | 类型 |
|---|---|---|
| CodeJudge | C4 容量决策记录 + 队列/失败率告警强化 | 配置 + 文档 |
| CodeJudge | C7 人工标注脚手架（样本导出、双人标注表、κ 计算、操作说明） | 新增脚本 + 文档 |
| CodeJudge | C10 生产默认关闭接口文档端点 | 配置默认值 |
| CodeJudge | C11 容器日志轮转与宿主机 logrotate 样例 | 配置 |
| zx-learn | Z3 关闭教师自助注册（网关白名单、后端端点、前端入口） | 代码 |
| zx-learn | Z10 CI 增加前端类型检查与构建 | CI 配置 |
| zx-learn | Z4 RAG 可用性启动探测与明确失败（不静默降级） | 代码 |
| zx-learn | Z11 手机号脱敏评估 | 仅评估，不改代码 |

### 1.2 明确不做

- 不生成/写入任何生产密钥、证书、口令。
- 不执行数据库迁移、不清理线上账号。
- 不压测、不部署、不提交 git。
- 不修改 `docs/quality/PROGRESS-REPORT-TEMPLATE.md`。
- 不编造 κ、RAG 可用性、告警送达时延、容量数字。
- Z11 手机号脱敏涉及 `UserVO` 全量消费方（本人资料、管理端、教师端），本次仅评估影响面；未确认消费方前不改序列化行为。

## 2. CodeJudge 实施规格

### 2.1 C4 容量决策（方案 A）

**决策**：接受当前判题容量——单实例约 **0.95 题/s**，3 实例受限并发下约 **0.96 题/s**；对外不承诺更高吞吐；扩容方向必须是“减少每题容器数 / 跨宿主机多实例”。

**改动**

1. `docs/PERF.md`：在 §3.7 末尾新增 `#### 6. 容量决策（2026-11-16）`，记录：
   - 选定方案 A 及理由（瓶颈=每题 5 次容器启停，实测加实例不涨吞吐）；
   - 约束公式：`实例数 × CJ_MQ_CONSUME_THREADS ≤ 宿主机 CPU 核数`；
   - 对外口径：仅承诺排队与失败率告警，不承诺 >0.95 题/s；
   - 复测触发条件：减少每题容器数或更换宿主后重跑 `scripts/measure-judge-throughput.py --n 150`。
2. `deploy/monitoring/prometheus/rules/codejudge-alerts.yml`：
   - 新增 `JudgeSystemErrorRateHigh`：`cj_judge_e2e_seconds_count{verdict="SE"}` 占全部终态样本 > 1%，持续 10 分钟，`severity: critical`；注释注明“阈值 1% 为首版，M5 双机复测后重标”。
   - 新增 `JudgeQueueBacklogCritical`：`judge_queue_backlog > 600` 持续 5 分钟，`severity: critical`；600 = 3 实例 × 0.95 题/s × 约 210s，与既有 200（单实例）形成两级。
   - 不新增 `# @tune` 锚点，避免 `scripts/recalibrate-alerts.py` 的锚点替换语义被改变。
3. `docs/SLO.md`：在容量/扩容小节补一句“SLO 只覆盖时延与错误率，不含吞吐承诺；容量口径见 PERF §3.7”。

**验收**

- `python -c "import yaml,sys;yaml.safe_load(open('deploy/monitoring/prometheus/rules/codejudge-alerts.yml',encoding='utf-8'));print('YAML OK')"` 通过。
- 若 `promtool` 可用：`promtool check rules deploy/monitoring/prometheus/rules/codejudge-alerts.yml` → SUCCESS；不可用则如实标阻塞。
- 150 条判题复测（C4-4）需运行栈，标阻塞，不得勾选。

### 2.2 C7 人工标注脚手架

**新增文件**

1. `scripts/export-human-eval-samples.py`
   - 复用 `eval-ai-quality.py` 的 `.env` 读取与 PostgreSQL 连接方式（`ai_review` 表）。
   - 按 verdict 分层（AC/WA/TLE/CE/RE 各 6 条，共 30），层内按 `id DESC` 取最新，排除 `model in ('degraded','builtin-fallback')`。
   - 输出 `docs/ai-eval/human/human-review-YYYYMMDD.csv`，列：
     `sample_id,verdict,problem_id,model,review_type,chars,content,`
     `A_factuality,A_executability,A_relevance,A_safety,A_failure_mode,A_note,`
     `B_factuality,B_executability,B_relevance,B_safety,B_failure_mode,B_note`。
   - `--template`：不连数据库，只写表头，供无运行栈时验证脚本可执行。
   - `--limit 30 --out <path> --force`；已存在且无 `--force` 时退出码 1。
   - 同时写 `docs/ai-eval/human/manifest-YYYYMMDD.json`（抽样窗口、各层条数、脚本版本）。
2. `scripts/compute-human-kappa.py`
   - 读取标注 CSV；校验 A/B 四维均为 1–5 整数、`failure_mode` 在 `FAILURE_MODES` 枚举内。
   - 计算四维的二次加权 Cohen's κ 与完全一致率；输出逐维结果与分歧样本 ID。
   - 退出码：0=全部维度 κ≥0.6；2=存在 κ<0.6（评测不可信）；1=输入非法。
   - `--selftest`：用内置 20 条固定数据验证 κ 计算（预期值写死在脚本内，容差 1e-6），不依赖外部文件。
3. `docs/ai-eval/HUMAN-ANNOTATION.md`
   - 标注流程、双人独立、分歧处理规则、rubric 版本锁定（v3）、κ 门槛 0.6、结果回填位置。
   - 明确“当前仅有脚手架，实际标注未执行”。

**验收**

- `python scripts/export-human-eval-samples.py --template` 生成含 18 列表头的 CSV。
- `python scripts/compute-human-kappa.py --selftest` 退出码 0。
- 真实 30 条标注与 κ：**阻塞**（需两名评审与数据库数据）。

### 2.3 C10 生产默认关闭接口文档

**改动**

- 7 个服务 `src/main/resources/application.yml` 中 `springdoc.api-docs.enabled` 与 `springdoc.swagger-ui.enabled` 的默认值由 `${CJ_SPRINGDOC_ENABLED:true}` 改为 `${CJ_SPRINGDOC_ENABLED:false}`：
  `judge-gateway`、`judge-auth`、`judge-user`、`judge-problem`、`judge-submission`、`judge-contest`、`judge-ai`。
- `.env.example` 增加显式条目：
  `CJ_SPRINGDOC_ENABLED=false`，注释“本地需要 /doc.html 时显式改 true；生产必须 false”。
- 同步更新 `docs/LAUNCH-CHECKLIST.md` D5 的说明：默认已关闭，生产只需确认未被覆盖为 true。

**验收**

- `grep -rn "CJ_SPRINGDOC_ENABLED:true" judge-*/src/main/resources/application.yml` 无匹配。
- 运行态 `/doc.html`、`/v3/api-docs` 返回 404：需启动服务，**阻塞**（本机无 mvn）。

### 2.4 C11 日志轮转

**改动**

- `docker-compose.yml` 顶部新增：
  ```yaml
  x-logging: &default-logging
    driver: json-file
    options:
      max-size: "50m"
      max-file: "5"
  ```
  为全部服务（基础设施 + app）增加 `logging: *default-logging`。
- 新增 `deploy/logrotate/codejudge`：
  - `/d/1/CodeJudge/logs/*.log`（宿主机直跑/dev 脚本）`daily`、`size 50M`、`rotate 7`、`compress`、`copytruncate`、`missingok`。
- `docs/LAUNCH-CHECKLIST.md` D6 更新为“容器：json-file 50m×5；宿主直跑：logrotate 样例已入库”。

**验收**

- `docker compose config` 退出码 0，且输出中 max-size/max-file 出现次数 ≥ 服务数。
- 错误风暴磁盘上界实测：**阻塞**（需运行栈）。

## 3. zx-learn 实施规格

### 3.1 Z3 关闭教师自助注册

**改动**

1. `zx-gateway/src/main/java/com/zhixing/gateway/config/JwtProperties.java`：从 `excludePaths` 删除 `"/teachers/register"`（保留 `/students/register`）。
2. `zx-user/src/main/java/com/zhixing/user/controller/TeacherController.java`：删除 `@PostMapping("/register")` 方法与 `validateRegister`；类注释改为“教师账号仅由管理员经 `POST /users` 创建”。
3. `zx-web/src/api/auth.ts`：删除 `registerTeacher`。
4. `zx-web/src/views/register/RegisterView.vue`：删除 `RoleKey`、角色单选组、`registerTeacher` 分支；提交固定走 `registerStudent`；文案改为“自助注册仅面向学员，教师账号由管理员创建”。
5. 文档：`grep -rn "/teachers/register" docs README.md` 命中的权限矩阵/接口文档改为“管理员创建”，无命中则记录“无文档引用”。

**验收**

- `grep -rn "teachers/register" zx-gateway zx-user zx-web/src` 无匹配（排除测试与历史文档）。
- `grep -n "registerTeacher" zx-web/src -r` 无匹配。
- 前端：`node zx-web/node_modules/vue-tsc/bin/vue-tsc.js --noEmit` 与 `node zx-web/node_modules/vite/bin/vite.js build` 退出码 0。
- 匿名 `POST /teachers/register` 返回 401/404（运行态）：**阻塞**（需启动栈）。
- 后端编译/单测：若 `mvn` 不可用则标阻塞，不勾选。

### 3.2 Z10 前端 CI

**改动**

- `.github/workflows/ci.yml` 新增 `frontend` job（与 `build` 并行）：
  - `actions/checkout@v4`、`actions/setup-node@v4`（node 20，`cache: npm`，`cache-dependency-path: zx-web/package-lock.json`）；
  - `npm ci`（若无 lock 文件则 `npm install`）；
  - `npm run typecheck`、`npm run build`，`working-directory: zx-web`。

**验收**

- 本地执行 typecheck/build 退出码 0。
- “故意制造类型错误让 CI 变红”：需推送触发 CI，**阻塞**。

### 3.3 Z4 RAG 可用性探测

**改动**

1. `zx-aigc/src/main/java/com/zhixing/aigc/config/LlmProperties.java`：新增
   - `ragFailFast`（`zx.llm.rag-fail-fast`，env `ZX_LLM_RAG_FAIL_FAST`，默认 `false`）；
   - `embeddingProbeOnStartup`（默认 `true`）。
2. 新增 `zx-aigc/src/main/java/com/zhixing/aigc/service/EmbeddingAvailabilityProbe.java`：
   - `ApplicationRunner`；启动时若 `apiKey` 为空：记录 ERROR + 指标 `zx_rag_embedding_available=0`；`ragFailFast=true` 时抛出 `IllegalStateException` 中止启动（错误信息含 `ZX_LLM_API_KEY`/`ZX_LLM_BASE_URL` 指引，不打印密钥）。
   - 若 `apiKey` 非空且 `embeddingProbeOnStartup=true`：调用 `EmbeddingService.embed("ping")`；捕获异常 → ERROR + `zx_rag_embedding_available=0`；`ragFailFast=true` 时中止启动。
   - 成功 → 指标置 1。
3. `zx-aigc/src/main/resources/application.yml`：暴露 `zx.llm.rag-fail-fast: ${ZX_LLM_RAG_FAIL_FAST:false}`。
4. `.env.example` 与 `docker-compose.prod.yml`：新增 `ZX_LLM_RAG_FAIL_FAST=true`（生产 fail-fast）。

**验收**

- 静态：`grep -rn "zx_rag_embedding_available" zx-aigc/src` 命中探针；`grep -n "rag-fail-fast" zx-aigc/src/main/resources/application.yml` 命中。
- 单测/启动验证：需 mvn 与真实额度，**阻塞**。
- 前端“不可用状态展示”（Z4-4）需新增可用性 API，本次不做，标阻塞。

### 3.4 Z11 手机号脱敏（仅评估）

- 输出评估结论到进度报告：`UserVO.cellPhone` 被 `/users/page`、`/users/{id}`、`/users/me`、教师端名单共同消费；全局序列化掩码会破坏本人资料页与管理员编辑回填。
- 正确做法是按调用场景返回掩码，并新增“查看完整号码”审计端点。涉及接口契约变更与新审计表，本次不实施，标阻塞待决策。

## 4. 验证与回填规则

- 每条任务必须至少有一个**本次真实执行**的命令输出；没有输出的一律“未完成/阻塞”。
- 允许的验证：YAML 解析、Python 脚本自测、前端 typecheck/build、`grep` 静态断言、`docker compose config`。
- 不可用环境（无 mvn、无运行栈、无 Docker daemon）必须在报告中写明命令与失败原因。
- 进度报告：`docs/quality/PROGRESS-CodeJudge-20261116.md`、`D:\1\zx-learn\docs\quality\PROGRESS-zx-learn-20261116.md`。
- `RELEASE-GATE.md` 只更新有命令证据的项；无证据项保持原状态。

## 5. 需用户确认

1. CodeJudge 接口文档默认关闭（本地需显式 `CJ_SPRINGDOC_ENABLED=true`）是否接受。
2. zx-learn 删除教师自助注册端点与前端入口（属行为变更）是否接受。
3. Z4 采用“生产 fail-fast、本地默认不 fail-fast”是否符合预期。
4. Z11 本次仅评估、不改代码是否接受。
