package com.codejudge.gateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 网关 JWT 验签工具
 *
 * <p>E-01：支持 {@code kid} 多钥灰度 —— 验签遍历全部密钥（active 钥优先），
 * 因此无 {@code kid} 的历史 token 与旧钥签发的 token 在灰度窗口内都不会被误判 401。
 */
@Slf4j
@Component
public class JwtUtils {

    private final List<SecretKey> verificationKeys;

    public JwtUtils(com.codejudge.gateway.config.JwtProperties properties) {
        List<SecretKey> keys = new ArrayList<>();
        List<SecretKey> actives = new ArrayList<>();
        if (properties.getKeys() != null) {
            for (com.codejudge.gateway.config.JwtProperties.Key k : properties.getKeys()) {
                if (k == null || !StringUtils.hasText(k.getKid()) || !StringUtils.hasText(k.getSecret())) {
                    continue;
                }
                SecretKey key = Keys.hmacShaKeyFor(k.getSecret().getBytes(StandardCharsets.UTF_8));
                keys.add(key);
                if (k.isActive()) {
                    actives.add(key);
                }
            }
        }
        if (keys.isEmpty()) {
            if (!StringUtils.hasText(properties.getSecret())) {
                throw new IllegalStateException(
                        "JWT 密钥未配置：请设置 CJ_JWT_SECRET，或 CJ_JWT_KEYS_0_KID/SECRET/ACTIVE …");
            }
            keys.add(Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8)));
            log.warn("[jwt-keys] 单钥模式（未配置 cj.jwt.keys）：换钥仍需两侧同窗口 + 全员重登");
        } else if (actives.size() == 1) {
            // active 钥排最前，减少灰度窗口内的无效验签尝试
            keys.remove(actives.get(0));
            keys.add(0, actives.get(0));
        }
        this.verificationKeys = List.copyOf(keys);
        if (!keys.isEmpty() && properties.getKeys() != null && !properties.getKeys().isEmpty()) {
            log.info("[jwt-keys] 多钥模式：验签密钥 {} 把（active 优先）", verificationKeys.size());
        }
    }

    /**
     * 解析结果：一次验签同时取出身份、角色与吊销校验所需的 jti/iat。
     *
     * @param userId     用户 id（token 必带；缺失或非法时为 {@code null}）
     * @param roleId     角色（user.type：1员工/2学员/3教师）；旧 token 无该 claim 时为 {@code null}
     * @param jti        JWT ID（吊销黑名单的锚点）；旧 token（升级前签发）无该 claim 时为 {@code null}
     * @param issuedAtMs 签发时刻毫秒（用户级吊销纪元比较用）；无 iat claim 时为 0
     */
    public record Identity(Long userId, Integer roleId, String jti, long issuedAtMs) {
    }

    /**
     * 单次解析出 {@link Identity}。
     * <p>
     * 原实现走 {@code isValid() + parseUserId() + parseRoleId()}，同一串 JWT 被
     * {@code parseSignedClaims} 验签/解析**三遍**。网关是每个请求的必经热路径，三次 HMAC
     * 验签纯属重复计算；合并为一次后签名只校验一遍，行为不变。
     *
     * @return {@code null} 表示 token 缺失、被篡改、已过期或载荷非法（调用方一律按未认证处理）
     */
    public Identity parseIdentity(String token) {
        try {
            Claims claims = parseClaims(token);
            // token 类型校验：只有 access token 可作为接口凭证。refresh token 有效期 30 天且
            // 经 HttpOnly Cookie 流转，若不校验 type，被盗 cookie 可直接当 access 用（30 天全权限）
            if (!"access".equals(claims.get("type"))) {
                return null;
            }
            java.util.Date issuedAt = claims.getIssuedAt();
            return new Identity(toLong(claims.get("userId")), toInt(claims.get("roleId")),
                    claims.getId(), issuedAt == null ? 0L : issuedAt.getTime());
        } catch (Exception e) {
            return null;
        }
    }

    public Long parseUserId(String token) {
        return toLong(parseClaims(token).get("userId"));
    }

    /**
     * 解析角色 claim（user.type：1员工/2学员/3教师）；旧 token 无该 claim 时返回 null
     */
    public Integer parseRoleId(String token) {
        try {
            return toInt(parseClaims(token).get("roleId"));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 按「active 钥优先」顺序遍历验签，命中即返回。
     * 无 {@code kid} 的历史 token 不做特判 —— 遍历自然会命中其签发钥。
     */
    private Claims parseClaims(String token) {
        JwtException last = null;
        for (SecretKey key : verificationKeys) {
            try {
                return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            } catch (ExpiredJwtException e) {
                // 签名正确、仅过期：换钥重试无意义
                throw e;
            } catch (JwtException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalArgumentException("token 为空或无法解析");
    }

    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number num) {
            return num.longValue();
        }
        return Long.valueOf(String.valueOf(v));
    }

    private Integer toInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number num) {
            return num.intValue();
        }
        return Integer.valueOf(String.valueOf(v));
    }
}
