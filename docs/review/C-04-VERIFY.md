# C-04 验证记录：成功态空正文（status=1 且 content 空白）

- 日期：2026-11-11
- 执行人：AI 协作代理（本机单机环境）
- 环境：`codejudge-pg`（PostgreSQL 5433，库 `judge_ai`）、`codejudge-judge-ai-1`、Maven 3.9.6
- 对应整改：`judge-ai/src/main/resources/db/migration/V2__ai_review_done_requires_content.sql`、
  `judge-ai/src/main/java/com/codejudge/ai/repository/AiReviewRepository.java#updateContent`
- 原始缺口（2026-11-07 复核）：`status=1` 且正文为空 33 条（历史 12 + 补齐语料 21）

---

## 1. V2 迁移执行确认

命令（只读）：

```powershell
Get-Content $env:TEMP\c04-q1.sql | docker exec -i codejudge-pg psql -U postgres -d judge_ai
```

输出（2026-11-11 14:17）：

```
 version |           description           | success |        installed_on        | execution_time
---------+---------------------------------+---------+----------------------------+----------------
 2       | ai review done requires content | t       | 2026-11-10 16:01:37.79677  |             63
 1       | << Flyway Baseline >>           | t       | 2026-09-30 09:36:08.332415 |              0
(2 rows)
```

结论：V2 已由 Flyway 在 **2026-11-10 16:01:37** 自动执行（judge-ai 启动时），`success = t`。

## 2. 订正前后对照（只读）

同一 SQL：

```sql
SELECT count(*) AS empty_done
FROM ai_review
WHERE status = 1 AND (content IS NULL OR length(btrim(content)) = 0);
```

- 订正前（2026-11-07 复核记录）：**33 条**
- 订正后（2026-11-11 14:17 实测）：**0 条**（见下节输出）

订正动作本身的行数证据（V2 把空正文成功态降级为 `status=2 + error_msg=EMPTY_CONTENT`）：

```
 corrected_by_v2 |           first_ts            |            last_ts
-----------------+-------------------------------+-------------------------------
              98 | 2026-11-10 16:01:37.816729+08 | 2026-11-10 16:01:37.816729+08
(1 row)
```

说明：`update_time` 与迁移 `installed_on` 同一毫秒级窗口，证明这 98 行由 V2 迁移改写（11-07 的 33 条是当时快照，之后又累积了新的空正文记录）。
当前库内状态分布：`status=0 → 6`、`status=1 → 194`、`status=2 → 106`。

## 3. 订正后巡检 = 0 行

```
 empty_done
------------
          0
(1 row)
```

## 4. 约束存在性

```
            conname            |                                                def
-------------------------------+---------------------------------------------------------------------------------------------------
 ck_ai_review_done_has_content | CHECK (((status IS DISTINCT FROM 1) OR ((content IS NOT NULL) AND (length(btrim(content)) > 0))))
(1 row)
```

## 5. 降级路径验证（`AiReviewRepositoryTest`）

命令：

```powershell
mvn -pl judge-ai -am test "-Dtest=AiReviewRepositoryTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

输出（2026-11-11 14:18，原始日志：[p2-ai-repo-test.log](file:///d:/1/CodeJudge/docs/review/raw-c04-20261111/p2-ai-repo-test.log)）：

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.066 s -- in com.codejudge.ai.repository.AiReviewRepositoryTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

覆盖的四条用例（`AiReviewRepositoryTest`）：

| 用例 | 输入 | 断言 |
|---|---|---|
| `blankContentDowngradedToFailure` | `content="  \n\t "`, `status=1` | 落库 `status=2`、`error_msg=EMPTY_CONTENT` |
| `nullContentDowngradedToFailure` | `content=null`, `status=1` | 落库 `status=2`、`error_msg=EMPTY_CONTENT` |
| `normalContentKeepsSuccess` | 正常正文, `status=1` | 保持 `status=1`、`error_msg=null` |
| `failureStatusUnaffected` | `content=""`, `status=2` | 保持 `status=2`，保留原错误信息 |

## 6. 结论

存量已清零（`empty_done = 0`，V2 于 2026-11-10 16:01:37 订正 98 行）/ CHECK 约束 `ck_ai_review_done_has_content` 已生效 / 降级路径已验证（`AiReviewRepositoryTest` 4/4 PASS）。
