-- ============================================================
-- V2__ai_review_done_requires_content.sql
-- 关闭「status=1（完成）但正文为空」的静默脏数据。
--
-- 背景：LLM 返回空串（流式零 delta / 网关返回空 body）时，旧代码仍写
--       status=1 + content=''。用户看到的是一篇空白点评，而监控/统计把它
--       算作成功，无法与「真成功」区分。应用侧已在唯一落库入口
--       AiReviewRepository#updateContent 降级为 status=2，本迁移提供
--       DB 层兜底，并订正历史存量。
--
-- 顺序很关键：必须先订正存量再建约束，否则 ADD CONSTRAINT 会因存量行
--       不满足条件而失败（Flyway 迁移失败 → judge-ai 启动失败）。
-- ============================================================

-- 1) 存量订正：空正文的成功记录降级为失败态（幂等，可重复执行）
UPDATE ai_review
SET status = 2,
    error_msg = COALESCE(NULLIF(error_msg, ''), 'EMPTY_CONTENT'),
    update_time = now()
WHERE status = 1
  AND (content IS NULL OR length(btrim(content)) = 0);

-- 2) DB 约束：成功态必须携带非空正文（幂等重建，便于重复执行/手工回放）
ALTER TABLE ai_review DROP CONSTRAINT IF EXISTS ck_ai_review_done_has_content;
ALTER TABLE ai_review ADD CONSTRAINT ck_ai_review_done_has_content
    CHECK (status IS DISTINCT FROM 1
           OR (content IS NOT NULL AND length(btrim(content)) > 0));
