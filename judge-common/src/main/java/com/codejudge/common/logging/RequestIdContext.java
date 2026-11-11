package com.codejudge.common.logging;

import com.codejudge.common.constants.Constant;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * requestId 上下文工具：生成带来源前缀的链路 id，并负责 MDC 的设置与恢复。
 *
 * <p>HTTP 入口由 {@code RequestIdInterceptor} 写入 MDC；跨 MQ 时写进 RocketMQ
 * 用户属性、消费端再回填 MDC（见 {@code RocketMQTemplate}/{@code RocketMQConsumerContainer}）。
 * 没有 HTTP 上下文的后台路径（补偿重投、定时对账、心跳）用 {@link #open(String)}
 * 生成带前缀的新 id，保证日志里不出现 {@code [req=]} 空值：
 * <ul>
 *   <li>{@code cmp-}：判题补偿重投（租约接管 / 滞留重发）—— 同一次扫描内的所有
 *       日志与派生消息共享一个 id，便于区分「用户首次提交」与「系统补偿」；</li>
 *   <li>{@code cron-}：定时对账 / 死信兜底扫描 —— 与工单提交无关联，独立成链；</li>
 *   <li>{@code hb-}：worker 心跳 —— 每次心跳一个 id，避免多 tick 混进同一条日志链；</li>
 *   <li>{@code mq-}：消费端发现旧消息未携带 requestId 时的兜底 id（配合 WARN）。</li>
 * </ul>
 */
public final class RequestIdContext {

    /**
     * RocketMQ 用户属性对键值有字符集限制（字母/数字/下划线/中划线）；
     * 客户端传入的 requestId 也会经过这个校验，避免日志注入与发送失败。
     */
    private static final String SAFE_ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private RequestIdContext() {
    }

    /** 生成 {prefix}{32 位 hex}，长度 ≤ 36，满足 MQ 用户属性限制。 */
    public static String generate(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /** 当前线程 MDC 中的 requestId；无上下文时返回 null（区别于 {@code Constant#getRequestId()} 的 ""）。 */
    public static String current() {
        return MDC.get(Constant.REQUEST_ID_HEADER);
    }

    /** 是否是可安全写入日志/MQ 属性/响应头的 id。 */
    public static boolean isSafe(String requestId) {
        return requestId != null && requestId.matches(SAFE_ID_PATTERN);
    }

    /**
     * 开启一个带新 requestId 的作用域，出作用域自动恢复进入前的值（无值则删除）。
     *
     * <p>用 try-with-resources 而不是手工 finally，避免后台线程池复用下漏写清理
     * 导致不同任务串号。
     */
    public static Scope open(String prefix) {
        String previous = current();
        MDC.put(Constant.REQUEST_ID_HEADER, generate(prefix));
        return new Scope(previous);
    }

    /** 恢复 MDC 到指定值；{@code previous} 为 null/空白时删除键。 */
    public static void restore(String previous) {
        if (previous == null || previous.isBlank()) {
            MDC.remove(Constant.REQUEST_ID_HEADER);
        } else {
            MDC.put(Constant.REQUEST_ID_HEADER, previous);
        }
    }

    /** 作用域句柄，close 时恢复进入前的 MDC。 */
    public static final class Scope implements AutoCloseable {

        private final String previous;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            restore(previous);
        }
    }
}
