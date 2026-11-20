# AI 点评人工标注规程（C7 / M7 方案 A）

> 状态：**脚手架已就绪，实际标注未执行**（截至 2026-11-16）。
> 本文不提供任何“准确率”“人类一致率”结论；结论只能在标注完成并跑出 κ 后回填。
> 关联：`docs/ai-eval/RUBRIC.md`（v3）、`docs/ai-eval/report-20261110.md`、`TEST-REPORT.md` §5。

## 1. 为什么做

现有评测的四个维度分数全部由 LLM-as-judge 给出（见 `report-20261110.md` §2），
属于“模型自评”，不能等同于人类判断。对外材料若使用“准确率”表述即为口径错误。
本规程用两名评审的独立打分给出人类金标准，并量化评审间一致性（κ）。

## 2. 样本

| 项 | 规则 |
|---|---|
| 来源 | 本地开发栈 `judge_ai.ai_review`，`status=1` 且正文非空 |
| 规模 | 30 条，AC/WA/TLE/CE/RE 各 6 条 |
| 抽样 | 层内按 `id DESC` 取最新；排除 `model in ('degraded','builtin-fallback')` 的模板兜底 |
| 导出 | `python scripts/export-human-eval-samples.py --limit 30` |
| 可复现 | 同目录 `manifest-<日期>.json` 记录抽样窗口与各层条数 |
| 禁止 | 按分数/内容挑样本；标注前查看 LLM judge 的分数（避免锚定） |

## 3. 打分

- 维度与 1–5 分档：完全沿用 `RUBRIC.md` v3，不新增维度、不临时改分档。
- 两名评审**独立**填写 CSV 的 `A_*` / `B_*` 列，互不查看对方结果。
- `failure_mode` 只能取以下枚举之一：
  `none` / `unverifiable_code` / `fact_contradiction` / `generic_boilerplate` /
  `unsafe_content` / `judgement_invalid` / `other`。
- `A_note` / `B_note` 仅记录“为什么给这个分”，不写整改建议。

## 4. 一致性与门槛

| 指标 | 计算 | 门槛 |
|---|---|---|
| 二次加权 Cohen's κ | 逐维度、A vs B | 每维 **≥ 0.6** |
| 完全一致率 | 同分样本占比 | 仅作参考，不单独设门槛 |

执行：

```bash
python scripts/compute-human-kappa.py docs/ai-eval/human/human-review-<日期>.csv
```

- 退出码 0：四维均达标，可作为金标准，进入第 6 节回填。
- 退出码 2：存在维度 < 0.6。先修订 rubric 示例与分档描述，**重新独立标注**；
  不得通过“讨论后改成一致”来抬高 κ。
- 退出码 1：输入非法（未填完、超范围、枚举错误）。

## 5. 分歧处理

1. 先各自保留原始分数，不修改 CSV。
2. 仅对“同一维度相差 ≥ 2 分”的样本做第三人仲裁，仲裁记录写入标注报告。
3. 仲裁不重算 κ；κ 始终基于两名评审的**首次独立**打分。

## 6. 结果回填

标注与 κ 完成后，只能由验收人回填：

| 文档 | 位置 | 允许的表述 |
|---|---|---|
| `TEST-REPORT.md` | §5 AI 效果 | “30 条双人标注，四维加权 κ = x.xx；与 LLM judge 的差异为 …” |
| `QUALITY-SUMMARY.md` | §3 / §6 | 删除任何未经金标准支撑的“准确率”表述 |
| `docs/ai-eval/report-20261110.md` | 文末追加 | 注明“本文分数为 LLM-as-judge，人工金标准结果见 …” |

## 7. 当前阻塞

- 需要 2 名具备判题经验的评审（外部资源）。
- 需要本地栈中有足量 `status=1` 的点评样本；样本不足时按实际条数记录，不得补造。

> 在上述条件满足前，报告与对外材料只允许写“评测为内部 LLM 回归基线，尚无人类金标准”。
