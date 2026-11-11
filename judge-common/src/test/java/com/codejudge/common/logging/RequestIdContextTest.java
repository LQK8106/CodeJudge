package com.codejudge.common.logging;

import com.codejudge.common.constants.Constant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * requestId 上下文单测：前缀生成、MDC 进入/恢复、以及 MQ 安全字符集校验。
 */
class RequestIdContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("open 生成带前缀 id，退出作用域后 MDC 恢复为空")
    void openGeneratesPrefixedIdAndRestores() {
        try (RequestIdContext.Scope ignored = RequestIdContext.open("cmp-")) {
            String id = RequestIdContext.current();
            assertThat(id).startsWith("cmp-");
            assertThat(RequestIdContext.isSafe(id)).isTrue();
        }
        assertThat(RequestIdContext.current()).isNull();
    }

    @Test
    @DisplayName("嵌套作用域退出后恢复进入前的 requestId")
    void restoresPreviousValue() {
        MDC.put(Constant.REQUEST_ID_HEADER, "http-orig-1");

        try (RequestIdContext.Scope ignored = RequestIdContext.open("cron-")) {
            assertThat(RequestIdContext.current()).startsWith("cron-");
        }

        assertThat(RequestIdContext.current()).isEqualTo("http-orig-1");
    }

    @Test
    @DisplayName("拒绝会破坏日志/MQ 属性的非法 id，接受常见安全字符")
    void validatesSafeCharset() {
        assertThat(RequestIdContext.isSafe("req-200_01")).isTrue();
        assertThat(RequestIdContext.isSafe(null)).isFalse();
        assertThat(RequestIdContext.isSafe(" ")).isFalse();
        assertThat(RequestIdContext.isSafe("bad id")).isFalse();
        assertThat(RequestIdContext.isSafe("id|1")).isFalse();
        assertThat(RequestIdContext.isSafe("a".repeat(65))).isFalse();
    }
}
