package com.codejudge.common.mq;

import com.codejudge.common.constants.Constant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产端 requestId 传播单测：当前线程 MDC 中的 requestId 必须写进 MQ 用户属性，
 * 无上下文/非法值时不得写入（由消费端生成兜底 id）。
 */
class RocketMQTemplateTest {

    private final RocketMQTemplate template =
            new RocketMQTemplate(new RocketMQProperties(), new ObjectMapper());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC 有 requestId → 写入用户属性，消息体保持不变")
    void carriesRequestIdFromMdc() {
        MDC.put(Constant.REQUEST_ID_HEADER, "req-abc_01");

        Message msg = template.buildMessage("judge_submission", "CREATED", "{\"a\":1}", 0);

        assertThat(msg.getUserProperty(Constant.REQUEST_ID_HEADER)).isEqualTo("req-abc_01");
        assertThat(new String(msg.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("无 MDC 上下文 → 不写属性（消费端兜底并 WARN，不阻塞发送）")
    void noPropertyWithoutContext() {
        Message msg = template.buildMessage("judge_submission", "CREATED", "{}", 0);

        assertThat(msg.getUserProperty(Constant.REQUEST_ID_HEADER)).isNull();
    }

    @Test
    @DisplayName("非法 requestId → 不写属性，避免 RocketMQ 校验异常中断业务发送")
    void ignoresUnsafeRequestId() {
        MDC.put(Constant.REQUEST_ID_HEADER, "bad id|1");

        Message msg = template.buildMessage("judge_submission", "CREATED", "{}", 0);

        assertThat(msg.getUserProperty(Constant.REQUEST_ID_HEADER)).isNull();
    }
}
