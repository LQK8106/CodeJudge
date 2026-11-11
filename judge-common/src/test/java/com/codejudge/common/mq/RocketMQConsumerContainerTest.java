package com.codejudge.common.mq;

import com.codejudge.common.constants.Constant;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费端 requestId 回填单测：
 * <ul>
 *   <li>新消息：把 MQ 用户属性里的 requestId 放进 MDC，处理器内可见；</li>
 *   <li>旧消息（无属性）：生成 {@code mq-} 兜底 id 且不丢消息；</li>
 *   <li>无论成功/失败，消费线程 MDC 都要清理，避免线程池复用导致串号。</li>
 * </ul>
 */
class RocketMQConsumerContainerTest {

    private static final String TOPIC = "judge_submission";
    private static final String TAG = "CREATED";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private RocketMQConsumerContainer container(MqHandler handler) {
        return new RocketMQConsumerContainer(new RocketMQProperties(), List.of(handler));
    }

    private MessageExt message(String requestId) {
        MessageExt msg = new MessageExt();
        msg.setTopic(TOPIC);
        msg.setTags(TAG);
        msg.setMsgId("msg-1");
        if (requestId != null) {
            msg.putUserProperty(Constant.REQUEST_ID_HEADER, requestId);
        }
        return msg;
    }

    private MqHandler recordingHandler(AtomicReference<String> seen) {
        return new MqHandler() {
            @Override
            public Set<String> subscribeTopics() {
                return Set.of(TOPIC);
            }

            @Override
            public boolean supports(String topic, String tag) {
                return TOPIC.equals(topic);
            }

            @Override
            public void handle(MessageExt message) {
                seen.set(MDC.get(Constant.REQUEST_ID_HEADER));
            }
        };
    }

    @Test
    @DisplayName("新消息：MDC 中可见消息携带的 requestId，处理完清理")
    void restoresRequestIdFromMessageProperty() {
        AtomicReference<String> seen = new AtomicReference<>();

        boolean ok = container(recordingHandler(seen)).handleOne(message("req-42"));

        assertThat(ok).isTrue();
        assertThat(seen.get()).isEqualTo("req-42");
        assertThat(MDC.get(Constant.REQUEST_ID_HEADER)).isNull();
    }

    @Test
    @DisplayName("旧消息无 requestId：生成 mq- 兜底 id，不抛异常不丢消息")
    void generatesFallbackForLegacyMessage() {
        AtomicReference<String> seen = new AtomicReference<>();

        boolean ok = container(recordingHandler(seen)).handleOne(message(null));

        assertThat(ok).isTrue();
        assertThat(seen.get()).startsWith("mq-");
        assertThat(MDC.get(Constant.REQUEST_ID_HEADER)).isNull();
    }

    @Test
    @DisplayName("处理异常：返回 false 触发重投，且 MDC 仍被清理")
    void clearsMdcOnFailure() {
        MqHandler failing = new MqHandler() {
            @Override
            public Set<String> subscribeTopics() {
                return Set.of(TOPIC);
            }

            @Override
            public boolean supports(String topic, String tag) {
                return true;
            }

            @Override
            public void handle(MessageExt message) {
                throw new IllegalStateException("boom");
            }
        };

        boolean ok = container(failing).handleOne(message("req-fail"));

        assertThat(ok).isFalse();
        assertThat(MDC.get(Constant.REQUEST_ID_HEADER)).isNull();
    }
}
