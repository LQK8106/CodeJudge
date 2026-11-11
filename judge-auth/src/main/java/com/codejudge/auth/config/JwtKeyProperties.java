package com.codejudge.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * JWT 密钥配置（E-01 多钥灰度换钥）。
 *
 * <p>两种形态，二选一生效：
 * <ul>
 *   <li><b>单钥（兼容旧部署）</b>：只配 {@code cj.jwt.secret}（env {@code CJ_JWT_SECRET}）。
 *       签发的 token 不带 {@code kid}，换钥仍需两侧同窗口、全员重登；</li>
 *   <li><b>多钥</b>：配 {@code cj.jwt.keys}。签发<b>只用</b> {@code active=true} 的那把，
 *       验签<b>遍历全部</b>（这样旧钥签的 token 在观察期内仍可用）。
 *       通过索引环境变量注入：{@code CJ_JWT_KEYS_0_KID / CJ_JWT_KEYS_0_SECRET / CJ_JWT_KEYS_0_ACTIVE}，
 *       第二把为 {@code CJ_JWT_KEYS_1_*}，以此类推。</li>
 * </ul>
 *
 * <p>两侧（judge-auth / judge-gateway）语义必须一致 —— 网关侧同名配置类见
 * {@code com.codejudge.gateway.config.JwtProperties}。
 */
@Data
@Component("cjJwtKeyProperties")
@ConfigurationProperties(prefix = "cj.jwt")
public class JwtKeyProperties {

    /** 单钥密钥（仅当 {@link #keys} 为空时使用） */
    private String secret;

    /** 多钥列表；为空则退化为单钥模式 */
    private List<Key> keys = new ArrayList<>();

    @Data
    public static class Key {

        /** 密钥标识，写入 JWT header 的 {@code kid} */
        private String kid;

        /** 密钥本体（≥32 字节，长度决定 HS256/384/512） */
        private String secret;

        /** 是否为签发密钥；有且仅有一把为 true（单把时可省略） */
        private boolean active;
    }
}
