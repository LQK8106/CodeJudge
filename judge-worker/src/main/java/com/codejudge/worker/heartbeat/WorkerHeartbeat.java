package com.codejudge.worker.heartbeat;

import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.logging.RequestIdContext;
import com.codejudge.worker.config.WorkerProperties;
import com.codejudge.worker.mq.WorkerIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * worker 心跳与负载上报（故障转移的探活数据源）。
 *
 * <ul>
 *   <li>心跳：{@code judge:worker:heartbeat:{workerId}} String，TTL 30s ——
 *       停止续期即自动"离线"，judge-submission 的租约扫描会在其任务到期后接管；</li>
 *   <li>负载：ZSet {@code judge:worker:load}，score = 在跑任务数。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerHeartbeat {

    private final StringRedisTemplate redis;
    private final WorkerIdentity identity;
    private final WorkerProperties properties;

    @Scheduled(fixedDelayString = "#{${CJ_WORKER_HEARTBEAT_INTERVAL:10000}}", initialDelay = 3_000)
    public void beat() {
        // 心跳线程无 HTTP 上下文：每次 tick 生成 hb- 前缀 id，避免日志里 [req=] 为空，
        // 也避免多个 tick 混进同一条日志链（tick 之间没有因果关系，不共享 id）。
        try (RequestIdContext.Scope ignored = RequestIdContext.open("hb-")) {
            redis.opsForValue().set(JudgeRedisKeys.WORKER_HEARTBEAT_PREFIX + identity.workerId(),
                    identity.workerId(), Duration.ofSeconds(properties.getHeartbeatTtlSeconds()));
            redis.opsForZSet().add(JudgeRedisKeys.WORKER_LOAD_ZSET, identity.workerId(), identity.runningTasks());
            log.debug("心跳上报：worker={} running={}", identity.workerId(), identity.runningTasks());
        } catch (Exception e) {
            log.warn("心跳上报失败（Redis 不可达）：{}", e.getMessage());
        }
    }
}
