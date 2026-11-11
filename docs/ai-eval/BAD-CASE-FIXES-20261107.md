# AI 点评 bad case 修复建议（基于 2026-11-07 评测）

> 输入证据：`docs/ai-eval/report-20261107.md`（40 条真实语料、12 条坏例、4 类失败模式）与 `docs/ai-eval/raw/review-*.json`。
> 本文只给根因定位与改法，不修改已定稿的 4 维 rubric，也不改动评测样本。

## 1. 坏例与根因映射

| 归类 | 条数 | 样本 id | 直接根因 | 严重度 |
|---|---|---|---|---|
| `truncation_repetition` | 3 | 4 / 5 / 38 | 上游生成中断被转成正文，半截内容以 `status=1` 落库 | P0 |
| `generic_boilerplate` | 2 | 100 / 202 | 兜底文案、模板首段被当作正常点评返回 | P0 |
| `fact_contradiction` | 2 | 206 / 13 | 206：产品侧引用不存在的异常栈；13：judge 评分理由自相矛盾 | P1 / P2 |
| `unverifiable_code` | 5 | 126 / 137 / 152 / 166 / 211 | 提示词要求「≤10 行关键片段」，片段天然无法编译 | P2 |

## 2. 逐条修复方案

### P0-1 上游异常不得再变成「可用正文」

**现象**：`review-4/5/38` 正文 50 余字，内容止于中断提示；`review-100` 正文就是「AI 服务暂时不可用…」。judge 只能按 1 分处理，且这类内容会进入 RAG 召回池。

**根因**：[LlmClient.java](../../judge-ai/src/main/java/com/codejudge/ai/service/LlmClient.java#L111-L115) 在 `onErrorResume` 里把异常替换成一段可读文本并**正常完成**；非流式分支的 [extractContent](../../judge-ai/src/main/java/com/codejudge/ai/service/LlmClient.java#L144-L147) 解析失败时也返回 `fallbackOnError()`。上层 [ReviewService.persistSuccess](../../judge-ai/src/main/java/com/codejudge/ai/service/ReviewService.java#L453-L479) 因此无法区分「模型写完」与「调用失败」。

**改法**：

1. 流式：不要 `Flux.just(提示文案)`，改为 `Flux.error(new LlmUnavailableException(e))`，或在该流的最后一个元素携带 `finishReason=ERROR` 的哨兵值，由 `ReviewService` 转成 `ERROR` SSE 事件 + `persistFailure`。
2. 非流式：`extractContent` 的结构异常直接抛 `LlmUnavailableException`，不再返回正文。
3. 保留「用户可读提示」的位置从正文挪到 **ERROR 事件的 message 字段**，前端 `useReviewStream` 直接展示，不写入 `ai_review.content`。

**验证**：临时把 `CJ_LLM_BASE_URL` 改错或断开网络，发起一次点评 → `ai_review.status=2`、`content` 为空、SSE 收到 `ERROR`；恢复配置后正常出点评。

### P0-2 空正文 / 降级正文不得以 `status=1` 落库

**现象**：补采 `reviewType=3` 时产生 6 条**正文为空但 `status=1`** 的点评；降级模板内容也按成功落库（`model=builtin-fallback`）。

**根因**：[persistSuccess](../../judge-ai/src/main/java/com/codejudge/ai/service/ReviewService.java#L463) 无条件写 `STATUS_DONE(1)`，只有「是否建向量」区分了 `degraded`（L470），状态与正文都没区分；库表也没有 `degraded` 列，评测脚本只能靠 `model` 白名单倒推。

**改法**：

```java
// persistSuccess 开头
String body = content == null ? "" : content.trim();
if (body.isEmpty()) {                       // 空正文 = 生成失败，不是“完成”
    persistFailure(reviewId, "EMPTY_CONTENT", persisted);
    return;
}
if (body.length() > reviewProperties.getMaxContentLength()) {
    body = body.substring(0, reviewProperties.getMaxContentLength());
}
reviewRepository.updateContent(reviewId, body, degraded, null, STATUS_DONE, null);
```

```sql
ALTER TABLE ai_review ADD COLUMN degraded boolean NOT NULL DEFAULT false;
UPDATE ai_review SET degraded = true WHERE model IN ('builtin-fallback', 'degraded');
ALTER TABLE ai_review ADD CONSTRAINT ck_ai_review_done_nonempty
  CHECK (status <> 1 OR length(btrim(content)) > 0);
```

同时把 `degraded` 写成列，而不是继续依赖 `model` 字符串约定（[RUBRIC 局限说明](report-20261107.md#L132) 已指出该口径缺陷）。

**验证**：

```sql
SELECT count(*) FROM ai_review WHERE status=1 AND length(btrim(content))=0;   -- 期望 0
SELECT count(*) FROM ai_review WHERE status=1 AND degraded=true;            -- 降级不应为 1
```

### P0-3 `finish_reason=length` 的截断要显式识别

**现象**：`review-202`（`reviewType=3`、AC 提交）正文 54 字，只有 `## 错因归类` 等首段锚点，属于生成被截断。

**根因**：`LlmClient` 只解析 `delta.content`，从不检查 `choices[0].finish_reason`；`max_tokens` 命中上限时与「正常结束」没有区别。

**改法**：

1. 在每个 chunk 解析 `finish_reason`，为 `length` 时置本次流 `TRUNCATED` 标志；
2. 以截断结束时：内容长度小于结构要求（例如少于首个二级标题后的 80 字）→ 走 `persistFailure("TRUNCATED")`；否则落 `STATUS_DONE` 但 `degraded=true`；
3. 更彻底的做法：对 `length` 触发一次「续写」请求（把已生成内容作为 assistant 前缀），当前 `CJ_LLM_MAX_TOKENS=4096` 对 `glm-5.3-flash` 偏紧，建议按点评类型分别设置上限（诊断 4096 / 相似题 2048 足够）。

**验证**：构造一道需要长分析的题（或临时把 `max_tokens` 调到 50）→ 不再出现「只有标题」的正文入库。

### P1-4 `reviewType=3` 模板与空结果处理

**现象**：`review-202` 首段为「## 错因归类」，对 **AC 提交**语义不符；6 条 type=3 点评正文为空。

**根因**：[ReviewPromptBuilder.outputFormat](../../judge-ai/src/main/java/com/codejudge/ai/agent/ReviewPromptBuilder.java#L104-L109) 的相似题模板第一段写死「错因归类」，而提交可能是 AC；RAG 未召回相似题时也没有「资料不足」的固定输出约束。

**改法**：

1. 模板首段改为「## 题目特征 / ## 可迁移知识点 / ## 推荐练习方向」，错因只在非 AC 时作为可选段；
2. 无相似题召回时，prompt 明确要求输出「当前题库暂无同类题目」并给 2 条知识点方向，禁止留空；
3. 前端对 `reviewType=3` 的正文做空值兜底，不把空串渲染成"已完成"。

**验证**：分别对 AC/WA 提交调用 `reviewType=3` 各 3 次 → 正文长度 ≥ 200 字、无空正文、无「错因归类」误用。

### P1-5 事实矛盾：异常栈与行号必须可核验（`review-206`）

**现象**：judge 原文「点评中 `ArithmeticException: / by zero at Main.main(Main.java:6)` 与实际代码不符，实际代码是 `a / (b - b)`」。

**根因**：点评在解释错因时凭记忆复述异常类型与行号，而 `ReviewContext` 未强制要求「引用即逐字复制」。

**改法**：

1. prompt 增加硬约束：「引用代码行必须逐字复制并标注行号；不确定时写『材料不足，无法判断』」（现有第 4 条只覆盖结论，不覆盖引用）；
2. 生成后置校验：抽取正文中 `Main.java:\d+` 与 `Exception` 类名，与提交源码/判题日志比对，不一致则该段落降级为「行号引用待核验」或整体 `degraded=true`；
3. 将判题日志（`compile_message`/`runtime_message`）以结构化字段注入 prompt，而不是让模型自行推断。

**验证**：对 review-206 同题同提交复跑 5 次 → 异常类型与行号命中率 100%，或全部出现「材料不足」。

### P2-6 片段不可编译：产品与评测口径要一处收敛（5 条）

**现象**：`review-126/137/152/166/211` 的代码块全部 javac 失败，原因均为**缺上下文而非语法错误**。

**根因**：产品提示词明确要求「**不要给出完整可提交的代码**…关键片段（不超过 10 行）」[ReviewPromptBuilder.java#L71-L72](../../judge-ai/src/main/java/com/codejudge/ai/agent/ReviewPromptBuilder.java#L71-L72)，而评测的「可执行性」维度按「能否编译」打分——两者的设计目标天然冲突。

**改法（二者择一，需明确记录口径）**：

- 产品侧：要求片段必须带上下文标注与闭合骨架（例如 `// 片段：位于 solve() 内，依赖 int[] a`），让片段在补全后可以编译；
- 评测侧：在 RUBRIC v3 中把「受提示词约束的片段」明确记为 **3 分中性**，不再记 2 分；此变更属于口径变更，必须整份重跑并单独说明，不能只重判这 5 条。

**不要做**：直接把这 5 条判为「产品 bug」而不改任一侧口径——问题会随每次评测重复出现。

### P2-7 judge 噪声（`review-13`）

**现象**：judge 理由「…与事实一致，但评分尺度 2 对应『明确指认错误错因』，此处评分有误」——自己承认打分错误，却仍输出 2 分。

**改法（评测脚本侧，不涉及产品）**：

1. judge prompt 增加「先列出事实证据 → 再给分 → 最后自检理由与分数是否一致」，不一致时输出 `{"failure_mode":"none","selfCheck":"inconsistent"}`；
2. 脚本把 `selfCheck=inconsistent` 视为解析失败并重试一次（不计入美化，重试结果照实记录）；
3. 可选：4/5 边界争议样本做第三次调用取众数，成本 +50%。

**验证**：重跑后 `parseErrors` 与 `selfCheck` 计数在报告 §3 中如实体现。

## 3. 优先级与预期效果

| 优先级 | 动作 | 覆盖坏例 | 修复后预期 |
|---|---|---|---|
| P0-1 | 异常不再转正文 | 4/5/38/100 | 归零 |
| P0-2 | 空/降级正文改失败态 | 6 条空正文 + 降级文本 | 不再进入成功语料 |
| P0-3 | 识别 `finish_reason=length` | 202 | 归零 |
| P1-4 | type=3 模板与空结果 | 202 类 | 归零 |
| P1-5 | 引用逐字复制 + 行号校验 | 206 | 归零 |
| P2-6 | 片段口径收敛 | 126/137/152/166/211 | 取决于口径，见上 |
| P2-7 | judge 自检 | 13 | 重判后预计 5/5 |

**合计**：P0–P1 完成后坏例 12 → 5（仅剩片段类）；若同时采纳 P2-6 的评测口径调整并整份重跑，坏例可 → 0。以上为按样本归因的估算，最终以重跑报告为准，不以估算代替实测。

## 4. 复验方式（一条命令）

```powershell
py -3 scripts/eval-ai-quality.py --limit 40 --runs 2 --workers 2 `
  --judge-model glm-4.5-flash --strategy diverse
```

验收断言（全部在 `docs/ai-eval/report-YYYYMMDD.md` 与 `raw/` 可见）：

1. `truncation_repetition`、`generic_boilerplate` 两类计数为 0；
2. 40 条样本正文均非空且 `status=1`、`degraded=false`；
3. 一致率 ≥ 98%、加权 κ ≥ 0.6；
4. 沙箱编译统计中「缺上下文」块数 ≤ 5 且全部落在同一归因（片段口径）。

## 5. 约束提醒

- 不改 4 维 rubric、不剔除低分样本、不按分数挑样本；
- 不把产品提示词要求的片段直接判为产品缺陷；
- 修复后必须整份重跑并保留新 `raw/` 目录，报告与旧版本不可混用。
