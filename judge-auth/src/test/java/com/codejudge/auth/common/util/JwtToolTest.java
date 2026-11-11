package com.codejudge.auth.common.util;

import com.codejudge.auth.config.JwtKeyProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 工具单元测试：验证签发与解析的正确性（含 E-01 多钥灰度）
 */
class JwtToolTest {

    private static final String SECRET_A = "zhixing-learn-jwt-test-secret-key-2024-for-unit-tests";
    private static final String SECRET_B = "codejudge-jwt-second-key-for-rotation-2026-unit-tests";

    /** 测试专用密钥（与生产环境无关，生产密钥由环境变量 CJ_JWT_SECRET 注入） */
    private final JwtTool jwtTool = new JwtTool(SECRET_A);

    private static JwtKeyProperties keys(boolean aActive, boolean bActive) {
        JwtKeyProperties props = new JwtKeyProperties();
        JwtKeyProperties.Key a = new JwtKeyProperties.Key();
        a.setKid("key-a");
        a.setSecret(SECRET_A);
        a.setActive(aActive);
        JwtKeyProperties.Key b = new JwtKeyProperties.Key();
        b.setKid("key-b");
        b.setSecret(SECRET_B);
        b.setActive(bActive);
        props.setKeys(List.of(a, b));
        return props;
    }

    /** 读取 JWT header 的 kid（仅解码 header 段，不验签） */
    private static String kidOf(String token) throws Exception {
        byte[] header = java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]);
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(header).path("kid").asText(null);
    }

    @Test
    void createAndParseAccessToken() {
        String token = jwtTool.createAccessToken(100L, 30 * 60 * 1000L);
        assertNotNull(token);
        assertEquals(100L, jwtTool.parseUserId(token));
    }

    @Test
    void createAndParseRefreshToken() {
        String token = jwtTool.createRefreshToken(200L, 30L * 24 * 60 * 60 * 1000L);
        assertNotNull(token);
        assertEquals(200L, jwtTool.parseUserId(token));
    }

    @Test
    void differentUsersProduceDifferentTokens() {
        String token1 = jwtTool.createAccessToken(1L, 60000L);
        String token2 = jwtTool.createAccessToken(2L, 60000L);
        assertNotEquals(token1, token2);
    }

    @Test
    void tokenTypeClaimIsWrittenAndParsable() {
        // type claim 是网关/续签链路区分 access 与 refresh 的依据（缺它 refresh 可当 access 用）
        String access = jwtTool.createAccessToken(100L, 30 * 60 * 1000L);
        String refresh = jwtTool.createRefreshToken(200L, 30L * 24 * 60 * 60 * 1000L);
        assertEquals("access", jwtTool.parseTokenType(access));
        assertEquals("refresh", jwtTool.parseTokenType(refresh));
    }

    @Test
    void everyTokenCarriesUniqueJti() {
        // jti 是登出吊销的锚点：黑名单按 jti 拉黑（judge:auth:bl:jti:{jti}）。
        // 每枚 token 必须携带唯一 jti，否则拉黑一枚 = 拉黑一批。
        String access = jwtTool.createAccessToken(100L, 30 * 60 * 1000L);
        String refresh = jwtTool.createRefreshToken(100L, 30L * 24 * 60 * 60 * 1000L);
        String accessAgain = jwtTool.createAccessToken(100L, 30 * 60 * 1000L);

        String jti = jwtTool.parse(access).getId();
        assertNotNull(jti, "access token 必须携带 jti claim");
        assertNotNull(jwtTool.parse(refresh).getId(), "refresh token 必须携带 jti claim");
        assertNotEquals(jti, jwtTool.parse(refresh).getId());
        assertNotEquals(jti, jwtTool.parse(accessAgain).getId());
    }

    @Test
    void multiKeySignsWithActiveKeyAndWritesKid() throws Exception {
        JwtTool tool = new JwtTool(keys(false, true));
        String token = tool.createAccessToken(100L, 60_000L);
        assertEquals("key-b", kidOf(token), "签发必须使用 active=true 的密钥");
        // 验签遍历全部：active 钥签的 token 自己当然能验
        assertEquals(100L, new JwtTool(keys(false, true)).parseUserId(token));
    }

    @Test
    void activeSwitchChangesIssuedKid() throws Exception {
        String oldKidToken = new JwtTool(keys(true, false)).createAccessToken(1L, 60_000L);
        String newKidToken = new JwtTool(keys(false, true)).createAccessToken(1L, 60_000L);
        assertEquals("key-a", kidOf(oldKidToken));
        assertEquals("key-b", kidOf(newKidToken));
        // 切换后仍能验旧钥签发的 token（双钥窗口成立的前提）
        assertEquals(1L, new JwtTool(keys(false, true)).parseUserId(oldKidToken));
    }

    @Test
    void legacyTokenWithoutKidFallsBackToConfiguredKey() throws Exception {
        // 旧部署：单钥签发的 token 不带 kid
        String legacy = new JwtTool(SECRET_A).createAccessToken(300L, 60_000L);
        assertNull(kidOf(legacy), "单钥模式签发的 token 不应带 kid");
        // 升级到双钥后，无 kid 的历史 token 仍必须可用，不得因缺字段被 401
        assertEquals(300L, new JwtTool(keys(false, true)).parseUserId(legacy));
    }

    @Test
    void tokenSignedByInactiveKeyStillVerifies() {
        String inactiveKeyToken = new JwtTool(keys(true, false)).createAccessToken(400L, 60_000L);
        assertEquals(400L, new JwtTool(keys(false, true)).parseUserId(inactiveKeyToken));
    }

    @Test
    void tokenSignedByUnknownKeyIsRejected() {
        JwtTool stranger = new JwtTool("unknown-key-not-configured-anywhere-2026-unit-test!!");
        String token = stranger.createAccessToken(500L, 60_000L);
        assertThrows(io.jsonwebtoken.JwtException.class, () -> new JwtTool(keys(false, true)).parse(token));
    }
}
