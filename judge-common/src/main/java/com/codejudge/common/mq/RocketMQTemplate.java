package com.codejudge.common.mq;

import com.codejudge.common.constants.Constant;
import com.codejudge.common.logging.RequestIdContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;

/**
 * RocketMQ 通用生产者薄封装（沉淀到 judge-common，供下单、秒杀等异步链路复用）。
 * <p>
 * 可靠性策略：实时投递失败不抛给业务（业务依赖"本地消息表 + 定时扫描"兜底补偿），
 * 这里仅记录日志并返回是否成功。MQ 不可用（如 name-server 未启动）时 start 失败但对象仍可注入，
 * send 返回 false，由调用方走本地消息表补偿，不影响事务回滚。
 */
@Slf4j
public class RocketMQTemplate {

    private final RocketMQProperties properties;
    private final MessageCodec codec;
    private DefaultMQProducer producer;

    public RocketMQTemplate(RocketMQProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.codec = new MessageCodec(objectMapper);
    }

    /** 启动生产者；MQ 不可用时不抛出，记录降级日志。 */
    public void start() {
        try {
            DefaultMQProducer p = new DefaultMQProducer(properties.getProducerGroup());
            p.setNamesrvAddr(properties.getNameServer());
            p.setRetryTimesWhenSendFailed(3);
            p.setSendMsgTimeout(3000);
            p.start();
            this.producer = p;
            log.info("RocketMQ 生产者启动成功，namesrv={}", properties.getNameServer());
        } catch (Exception e) {
            // MQ 挂了：记录告警，由本地消息表 + 定时扫描补偿，不让业务受影响
            log.error("RocketMQ 生产者启动失败，消息将走本地消息表补偿：{}", e.getMessage());
            this.producer = null;
        }
    }

    public void shutdown() {
        if (producer != null) {
            producer.shutdown();
        }
    }

    /** 发送普通消息（不延迟） */
    public boolean send(String topic, String tag, Object body) {
        return send(topic, tag, body, 0);
    }

    /**
     * 发送消息（可指定延迟级别）
     *
     * @param delayLevel 0 表示不延迟；>0 对应 broker 配置的 messageDelayLevel 索引
     */
    public boolean send(String topic, String tag, Object body, int delayLevel) {
        return sendPayload(topic, tag, codec.toJson(body), delayLevel);
    }

    /** 直接发送 JSON 报文体（本地消息表补偿时使用，避免二次序列化） */
    public boolean sendPayload(String topic, String tag, String payload) {
        return sendPayload(topic, tag, payload, 0);
    }

    private boolean sendPayload(String topic, String tag, String payload, int delayLevel) {
        if (producer == null) {
            return false;
        }
        try {
            Message msg = buildMessage(topic, tag, payload, delayLevel);
            SendResult result = producer.send(msg);
            boolean ok = result != null && result.getSendStatus() == SendStatus.SEND_OK;
            log.info("发送 MQ 消息：topic={}, tag={}, ok={}", topic, tag, ok);
            return ok;
        } catch (Exception e) {
            log.error("发送 MQ 消息失败：topic={}, tag={}, err={}", topic, tag, e.getMessage());
            return false;
        }
    }

    /**
     * 构建消息并把当前线程的 requestId 写进 MQ 用户属性（跨 MQ 链路追踪）。
     *
     * <p>选择用户属性而不是消息体字段：不改 DTO / 不增加业务序列化体积，
     * 且 MQ 控制台可直接看到；旧消息没有该属性时消费端会兜底生成并告警，
     * 因此不存在兼容性问题。
     *
     * <p>无请求上下文（如未覆盖的后台线程）时不写属性，由消费端生成 {@code mq-} 兜底 id。
     */
    Message buildMessage(String topic, String tag, String payload, int delayLevel) {
        Message msg = new Message(topic, tag, payload.getBytes(StandardCharsets.UTF_8));
        String requestId = RequestIdContext.current();
        if (RequestIdContext.isSafe(requestId)) {
            msg.putUserProperty(Constant.REQUEST_ID_HEADER, requestId);
        }
        if (delayLevel > 0) {
            msg.setDelayTimeLevel(delayLevel);
        }
        return msg;
    }
}