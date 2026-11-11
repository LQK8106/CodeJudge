package com.codejudge.ai.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * AiReviewRepository 落库契约单测：<b>成功态（status=1）不得携带空白正文</b>。
 *
 * <p>对应缺陷：LLM 返回空串时仍写 status=1，产生「已完成但 content=''」的脏记录，
 * 用户看到空白点评、监控却计为成功。修复在唯一落库入口
 * {@link AiReviewRepository#updateContent} 收口降级为 status=2 + EMPTY_CONTENT，
 * 另有 V2 Flyway 迁移的 DB CHECK 约束兜底。
 *
 * <p>运行：mvn -pl judge-ai test -Dtest=AiReviewRepositoryTest
 */
@ExtendWith(MockitoExtension.class)
class AiReviewRepositoryTest {

    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAILED = 2;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AiReviewRepository repository;

    /** 捕获 UPDATE 的参数数组：[content, tokensIn, tokensOut, status, errorMsg, id]。 */
    private Object[] captureUpdateArgs(Long id, String content, int status, String errorMsg) {
        repository.updateContent(id, content, null, null, status, errorMsg);
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("成功态 + 纯空白正文 → 降级为失败态并写 EMPTY_CONTENT")
    void blankContentDowngradedToFailure() {
        Object[] args = captureUpdateArgs(7L, "  \n\t ", STATUS_DONE, null);

        assertThat(args[3]).isEqualTo(STATUS_FAILED);
        assertThat(args[4]).isEqualTo(AiReviewRepository.ERROR_EMPTY_CONTENT);
    }

    @Test
    @DisplayName("成功态 + null 正文 → 同样降级，不允许落成 status=1")
    void nullContentDowngradedToFailure() {
        Object[] args = captureUpdateArgs(8L, null, STATUS_DONE, null);

        assertThat(args[3]).isEqualTo(STATUS_FAILED);
        assertThat(args[4]).isEqualTo(AiReviewRepository.ERROR_EMPTY_CONTENT);
    }

    @Test
    @DisplayName("成功态 + 正常正文 → 保持 status=1，error_msg 不被覆盖")
    void normalContentKeepsSuccess() {
        Object[] args = captureUpdateArgs(9L, "复杂度 O(n log n)，建议改用双指针", STATUS_DONE, null);

        assertThat(args[3]).isEqualTo(STATUS_DONE);
        assertThat(args[4]).isNull();
        assertThat(args[0]).isEqualTo("复杂度 O(n log n)，建议改用双指针");
    }

    @Test
    @DisplayName("失败态 + 空正文 → 不受成功态守卫影响（正常失败路径）")
    void failureStatusUnaffected() {
        Object[] args = captureUpdateArgs(10L, "", STATUS_FAILED, "LLM 超时");

        assertThat(args[3]).isEqualTo(STATUS_FAILED);
        assertThat(args[4]).isEqualTo("LLM 超时");
    }
}
