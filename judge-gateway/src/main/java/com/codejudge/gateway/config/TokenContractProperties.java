package com.codejudge.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * token claim 契约版本校验配置（E-04）。
 *
 * <p>背景：网关按 {@code claims.get("type")} 判定 access/refresh（见 {@code JwtUtils}），
 * 而该 claim 由 judge-auth 签发。两侧代码若不同步（键名、取值、是否必填任一变化），
 * 合法请求会被判 401 —— 症状发生在用户侧，发布窗口内看不到。本配置让网关**启动时**
 * 读取 judge-auth 的 {@code /actuator/info} 中声明的契约版本并比对，不一致直接拒绝启动。
 *
 * <p>契约锚定什么（变更任一项必须递增版本）：
 * <ul>
 *   <li>claim 键名：{@code type}</li>
 *   <li>允许取值：{@code access} / {@code refresh}</li>
 *   <li>必填性：两枚 token 都必须携带</li>
 * </ul>
 * 本次只做版本声明与校验，<b>不改变 claim 结构</b>。
 */
@Data
@Component("cjTokenContractProperties")
@ConfigurationProperties(prefix = "cj.security.contract-check")
public class TokenContractProperties {

    /**
     * 是否启用启动期契约校验。本地单测/离线开发可置 false；
     * 生产保持 true —— 关闭即回到「发布顺序错误只能由用户发现」的旧状态。
     */
    private boolean enabled = true;

    /** judge-auth 基地址（与路由共用 {@code GW_AUTH_URI}） */
    private String authBaseUrl = "http://judge-auth:9081";

    /** 本端要求的契约版本（与 auth 侧 {@code CJ_TOKEN_CONTRACT_VERSION} 同一变量） */
    private String expected = "v1";

    /**
     * 对端**不可达**时是否拒绝启动。
     * <ul>
     *   <li>{@code true}（默认，生产安全）：读不到 = 无法证明两侧一致 → 拒绝启动；</li>
     *   <li>{@code false}（本地开发）：WARN 后放行，避免「auth 还没起来」导致网关起不来。</li>
     * </ul>
     * 注意：**版本不一致**不受此开关影响，永远拒绝启动（不允许降级放行）。
     */
    private boolean failOnUnreachable = true;

    /** 读取对端 /actuator/info 的最大尝试次数（含首次） */
    private int maxAttempts = 5;

    /** 两次尝试之间的间隔（毫秒），用于等待 auth 启动完成 */
    private long retryDelayMs = 1000L;

    /** 单次请求超时（毫秒），避免启动被慢对端拖死 */
    private int requestTimeoutMs = 1500;
}
