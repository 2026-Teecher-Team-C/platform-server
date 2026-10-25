package com.teecherteamc.platform.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 관리자 access 토큰(JWT) 서명·검증 키. 외부 인증 서버가 없어서 api-server가 HS256 비밀키로 직접 서명하고 검증한다.
 *
 * <p>비밀키(JWT_SECRET)는 기본값이 없다. 비어 있거나 32바이트(HS256 최소 키 길이)보다 짧으면 기동을 멈춘다 —
 * 짧은 키로 조용히 떠서 토큰을 위조당하는 것보다 낫다.
 */
@Configuration
public class JwtConfig {

    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecretKey jwtSecretKey(@Value("${platform.auth.jwt.secret:}") String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다. 관리자 access 토큰 서명 키입니다.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    /** 서명과 만료(exp)를 검사한다. 만료는 Spring Security 기본값대로 60초까지 시계 오차를 봐준다. */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
