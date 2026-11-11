package com.codejudge.gateway.util;

import com.codejudge.gateway.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网关多钥验签测试（E-01）：覆盖「无 kid 历史 token」「多钥遍历命中」「未知钥拒绝」。
 */
class JwtUtilsKeyTest {

    private static final String SECRET_A = "zhixing-learn-jwt-test-secret-key-2024-for-unit-tests";
    private static final String SECRET_B = "codejudge-jwt-second-key-for-rotation-2026-unit-tests";

    private static JwtProperties dualKeys() {
        JwtProperties props = new JwtProperties();
        JwtProperties.Key a = new JwtProperties.Key();
        a.setKid("key-a");
        a.setSecret(SECRET_A);
        a.setActive(false);
        JwtProperties.Key b = new JwtProperties.Key();
        b.setKid("key-b");
        b.setSecret(SECRET_B);
        b.setActive(true);
        props.setKeys(List.of(a, b));
        return props;
    }

    private static String token(String secret, String kid, long userId) {
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .claim("userId", userId)
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L));
        if (kid != null) {
            builder.header().keyId(kid).and();
        }
        return builder.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @Test
    void legacyTokenWithoutKidIsAccepted() {
        JwtUtils jwtUtils = new JwtUtils(dualKeys());
        // 旧部署单钥签发：header 无 kid
        assertTrue(jwtUtils.isValid(token(SECRET_A, null, 300L)));
        assertEquals(300L, jwtUtils.parseIdentity(token(SECRET_A, null, 300L)).userId());
    }

    @Test
    void tokenSignedByInactiveKeyIsAccepted() {
        JwtUtils jwtUtils = new JwtUtils(dualKeys());
        assertEquals(400L, jwtUtils.parseIdentity(token(SECRET_A, "key-a", 400L)).userId());
    }

    @Test
    void tokenSignedByActiveKeyIsAccepted() {
        JwtUtils jwtUtils = new JwtUtils(dualKeys());
        assertEquals(401L, jwtUtils.parseIdentity(token(SECRET_B, "key-b", 401L)).userId());
    }

    @Test
    void tokenSignedByUnknownKeyIsRejected() {
        JwtUtils jwtUtils = new JwtUtils(dualKeys());
        String forged = token("unknown-key-not-configured-anywhere-2026-unit-test!!", "key-a", 500L);
        assertFalse(jwtUtils.isValid(forged));
        assertNull(jwtUtils.parseIdentity(forged));
    }

    @Test
    void singleKeyFallbackStillWorks() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET_A);
        JwtUtils jwtUtils = new JwtUtils(props);
        assertEquals(600L, jwtUtils.parseIdentity(token(SECRET_A, null, 600L)).userId());
    }
}
