package com.codejudge.gateway.contract;

import com.codejudge.gateway.config.TokenContractProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 启动期 token claim 契约校验（E-04）。
 *
 * <p>网关的鉴权强依赖 judge-auth 签发的 {@code type} claim（取值 {@code access}/{@code refresh}）。
 * 两侧版本错位时，网关会把合法 token 判 401 —— 这个故障只在用户侧可见，发布窗口内
 * 日志里没有任何异常。因此网关在**启动时**读取 auth 的 {@code /actuator/info} 中
 * {@code app.token-claim-contract} 声明的契约版本，与本端 {@code cj.security.contract-check.expected}
 * 比对：
 * <ul>
 *   <li><b>版本不一致</b> → 拒绝启动（不受 {@code fail-on-unreachable} 影响，永不降级放行）；</li>
 *   <li><b>对端不可达 / 未声明版本</b> → 由 {@code fail-on-unreachable} 决定：
 *       {@code true}（生产默认）拒绝启动，{@code false}（本地开发）WARN 放行。</li>
 * </ul>
 *
 * <p>抛出异常即让 {@code SpringApplication#run} 失败、进程以非 0 退出 —— 发布顺序错误
 * 会停在「新实例起不来」，而不是「新实例起来了但所有用户 401」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenContractCheckRunner implements ApplicationRunner {

    /** 对端契约版本的暴露位置（见两侧 application.yml 的 info.app 段） */
    private static final String INFO_PATH = "/actuator/info";
    private static final String CONTRACT_KEY = "token-claim-contract";

    private final TokenContractProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("[contract-check] 跳过：cj.security.contract-check.enabled=false（本地/离线场景）");
            return;
        }

        String local = properties.getExpected();
        String url = properties.getAuthBaseUrl() + INFO_PATH;

        FetchResult result = fetchWithRetry(url);

        if (!result.ok()) {
            onUnreachable(url, local, result.error());
            return;
        }

        String remote = result.contractVersion();
        if (remote == null || remote.isBlank()) {
            // 对端可达但没有声明版本：常见于「旧版 auth 先上、新版 gateway 后上」。
            // 无法证明两侧一致，与不可达同样处理（生产拒绝启动）。
            onUnreachable(url, local, "对端 /actuator/info 缺少 app." + CONTRACT_KEY + "（对端为未声明契约的旧版本）");
            return;
        }

        if (!local.equals(remote)) {
            log.error("""
                    [contract-check] 拒绝启动：token claim 契约版本不一致
                      · 本地(gateway)版本 : {}
                      · 对端(auth)版本   : {}
                      · 对端地址         : {}
                      · 处理建议         : 先发布 judge-auth、确认 /actuator/info 已返回新版本，再发布 judge-gateway；
                                           或将两侧 CJ_TOKEN_CONTRACT_VERSION 对齐为同一值。
                                           禁止降级放行 —— claim 结构不一致时合法请求会被判 401，故障只在用户侧可见。
                    """, local, remote, url);
            throw new IllegalStateException("token claim 契约版本不一致: 本地=" + local
                    + " 对端=" + remote + " url=" + url);
        }

        log.info("[contract-check] 通过：本地={} 对端={} url={}", local, remote, url);
    }

    /**
     * 对端不可达 / 无法证明契约一致时的处置。与版本不一致分开，便于日志区分根因。
     */
    private void onUnreachable(String url, String local, String reason) {
        if (properties.isFailOnUnreachable()) {
            log.error("""
                    [contract-check] 拒绝启动：无法读取 judge-auth 的契约版本
                      · 本地(gateway)版本 : {}
                      · 对端地址         : {}
                      · 失败原因         : {}
                      · 处理建议         : 确认 judge-auth 已启动且 /actuator/info 可达（management.info.env.enabled=true）；
                                           本地无 auth 时置 CJ_CONTRACT_CHECK_FAIL_UNREACHABLE=false 放宽（生产必须为 true）。
                    """, local, url, reason);
            throw new IllegalStateException("无法读取 judge-auth 契约版本: " + reason + " url=" + url);
        }
        log.warn("[contract-check] WARN 放行：无法读取 judge-auth 的契约版本（本地宽松模式）"
                + " 本地={} url={} 原因={}", local, url, reason);
    }

    /** 读取对端 /actuator/info，失败按下标重试；返回最后一次结果 */
    private FetchResult fetchWithRetry(String url) {
        int attempts = Math.max(1, properties.getMaxAttempts());
        String lastError = "未知错误";
        for (int i = 1; i <= attempts; i++) {
            try {
                String body = WebClient.builder().build()
                        .get().uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofMillis(properties.getRequestTimeoutMs()));
                if (body == null || body.isBlank()) {
                    lastError = "响应为空";
                } else {
                    String version = extractContractVersion(body);
                    if (version != null && !version.isBlank()) {
                        return FetchResult.success(version);
                    }
                    lastError = "响应中缺少 app." + CONTRACT_KEY;
                }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            if (i < attempts) {
                log.warn("[contract-check] 第 {}/{} 次读取 {} 失败（{}），{}ms 后重试",
                        i, attempts, url, lastError, properties.getRetryDelayMs());
                try {
                    Thread.sleep(properties.getRetryDelayMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return FetchResult.failure(lastError);
    }

    /** 解析 {@code info.app.token-claim-contract}；兼容曾被平铺在顶层的写法 */
    private String extractContractVersion(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode node = root.path("app").path(CONTRACT_KEY);
            if (node.isMissingNode() || node.isNull()) {
                node = root.path(CONTRACT_KEY);
            }
            return node.isValueNode() ? node.asText() : null;
        } catch (Exception e) {
            log.warn("[contract-check] 解析对端 /actuator/info 失败: {}", e.toString());
            return null;
        }
    }

    private record FetchResult(boolean ok, String contractVersion, String error) {

        static FetchResult success(String contractVersion) {
            return new FetchResult(true, contractVersion, null);
        }

        static FetchResult failure(String error) {
            return new FetchResult(false, null, error);
        }
    }
}
