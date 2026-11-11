package com.codejudge.auth.common.util;

import com.codejudge.auth.common.constants.JwtConstants;
import com.codejudge.auth.config.JwtKeyProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * JWT 工具：签发与解析。
 *
 * <p>E-01：支持 {@code kid} 多钥灰度换钥 —— 签发只用 active 钥（header 写 {@code kid}），
 * 验签遍历全部钥（含无 {@code kid} 的历史 token，回退默认钥）。单钥配置时行为与改造前一致，
 * 但会打 WARN 提示无法灰度。
 */
@Slf4j
@Component
public class JwtTool {

    private final List<KeyEntry> entries;
    private final String activeKid;

    @Autowired
    public JwtTool(JwtKeyProperties properties) {
        List<KeyEntry> configured = new ArrayList<>();
        List<KeyEntry> actives = new ArrayList<>();
        if (properties.getKeys() != null) {
            for (JwtKeyProperties.Key k : properties.getKeys()) {
                if (k == null || !StringUtils.hasText(k.getKid()) || !StringUtils.hasText(k.getSecret())) {
                    continue;
                }
                KeyEntry entry = new KeyEntry(k.getKid(),
                        Keys.hmacShaKeyFor(k.getSecret().getBytes(StandardCharsets.UTF_8)));
                configured.add(entry);
                if (k.isActive()) {
                    actives.add(entry);
                }
            }
        }

        if (configured.isEmpty()) {
            // 旧配置降级：只有 cj.jwt.secret，必须能正常启动（约束：不得因缺 keys 而启动失败）
            if (!StringUtils.hasText(properties.getSecret())) {
                throw new IllegalStateException(
                        "JWT 密钥未配置：请设置 CJ_JWT_SECRET，或 CJ_JWT_KEYS_0_KID/SECRET/ACTIVE …");
            }
            this.entries = List.of(new KeyEntry(null,
                    Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8))));
            this.activeKid = null;
            log.warn("[jwt-keys] 单钥模式（未配置 cj.jwt.keys）：换钥仍需两侧同窗口 + 全员重登；"
                    + "灰度换钥请配置 CJ_JWT_KEYS_0_KID/SECRET/ACTIVE");
            return;
        }

        if (actives.size() > 1) {
            throw new IllegalStateException("cj.jwt.keys 中 active=true 的密钥只能有一把，实际 " + actives.size());
        }
        if (actives.isEmpty() && configured.size() > 1) {
            throw new IllegalStateException("cj.jwt.keys 配置了多把密钥但没有任何 active=true，无法确定签发密钥");
        }
        KeyEntry signing = actives.isEmpty() ? configured.get(0) : actives.get(0);
        if (actives.isEmpty()) {
            log.warn("[jwt-keys] cj.jwt.keys 未标记 active，单把密钥 {} 自动作为签发钥", signing.kid());
        }
        // 签发钥排最前：无 kid 的旧 token 在灰度窗口内通常用它签发，命中更快
        List<KeyEntry> ordered = new ArrayList<>(configured.size());
        ordered.add(signing);
        for (KeyEntry e : configured) {
            if (e != signing) {
                ordered.add(e);
            }
        }
        this.entries = List.copyOf(ordered);
        this.activeKid = signing.kid();
        log.info("[jwt-keys] 多钥模式：active={} 参与验签={}", activeKid, entries.stream().map(KeyEntry::kid).toList());
    }

    /** 单钥构造（单测/兼容）：签发的 token 不带 kid */
    public JwtTool(String secret) {
        this.entries = List.of(new KeyEntry(null, Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))));
        this.activeKid = null;
    }

    public String createAccessToken(Long userId, long ttlMillis) {
        return createToken(userId, null, ttlMillis, true);
    }

    /**
     * 签发 access token，并将用户类型（user.type：1员工/2学员/3教师）写入 role claim，
     * 供网关透传、下游服务做接口级角色校验（如知识库上传的教师权限）。
     */
    public String createAccessToken(Long userId, Integer role, long ttlMillis) {
        return createToken(userId, role, ttlMillis, true);
    }

    public String createRefreshToken(Long userId, long ttlMillis) {
        return createToken(userId, null, ttlMillis, false);
    }

    private String createToken(Long userId, Integer role, long ttlMillis, boolean access) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttlMillis);
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                // jti（JWT ID）：每枚 token 的唯一指纹，是「登出吊销」的锚点 ——
                // 黑名单按 jti 拉黑（judge:auth:bl:jti:{jti}），网关/续签入口据此拒绝已吊销 token
                .id(java.util.UUID.randomUUID().toString())
                .claim(JwtConstants.PAYLOAD_USER_KEY, userId)
                .claim("type", access ? "access" : "refresh")
                .issuedAt(now)
                .expiration(expiry);
        if (activeKid != null) {
            // 多钥模式：header 带 kid，验签方据此/或遍历命中对应密钥
            builder.header().keyId(activeKid).and();
        }
        builder.signWith(entries.get(0).key());
        if (role != null) {
            builder.claim(JwtConstants.PAYLOAD_ROLE_KEY, role);
        }
        return builder.compact();
    }

    public Long parseUserId(String token) {
        Claims claims = parse(token);
        Object userId = claims.get(JwtConstants.PAYLOAD_USER_KEY);
        if (userId instanceof Number num) {
            return num.longValue();
        }
        return Long.valueOf(String.valueOf(userId));
    }

    /** 解析角色 claim（user.type：1员工/2学员/3教师）；旧 token 无该 claim 时返回 null */
    public Integer parseRole(String token) {
        Object role = parse(token).get(JwtConstants.PAYLOAD_ROLE_KEY);
        if (role instanceof Number num) {
            return num.intValue();
        }
        return role == null ? null : Integer.valueOf(String.valueOf(role));
    }

    /**
     * 验签并解析：按「签发钥优先」顺序遍历全部密钥。
     * <p>无 {@code kid} 的历史 token 不特殊处理 —— 遍历命中即通过，因此灰度窗口内不会因缺字段被拒。
     */
    public Claims parse(String token) {
        JwtException last = null;
        for (KeyEntry entry : entries) {
            try {
                return Jwts.parser().verifyWith(entry.key()).build().parseSignedClaims(token).getPayload();
            } catch (ExpiredJwtException e) {
                // 签名已通过，只是过期：继续换钥重试没有意义
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

    /**
     * 解析 token 的类型标记（{@code type} claim：access / refresh）。
     * <p>HMAC 是对称签名，密钥即"公钥"，不存在可公开分发的 JWK ——
     * 历史上的 {@code GET /jwks} 端点会把 {@code getEncoded()}（即密钥本体）匿名返回，
     * 等于交出任意伪造身份的能力，已随本次加固删除；该方法仅供服务内部区分 token 类型。
     */
    public String parseTokenType(String token) {
        Object type = parse(token).get("type");
        return type == null ? null : String.valueOf(type);
    }

    private record KeyEntry(String kid, SecretKey key) {
    }
}
