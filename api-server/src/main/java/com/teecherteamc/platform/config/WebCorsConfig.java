package com.teecherteamc.platform.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 관리 콘솔(Vercel·로컬 vite)이 브라우저에서 `/api/**`를 직접 호출할 수 있게 출처를 허용한다.
 * gRPC(에이전트)와 actuator는 브라우저가 부르지 않으므로 대상이 아니다.
 *
 * <p>로그인은 세션 쿠키라서 자격 증명(쿠키)을 허용한다. 그래서 출처는 `*`가 아니라 정확한 주소만 둔다.
 * 이 설정은 SecurityConfig의 {@code http.cors()}가 보안 필터 안에서 적용한다 — 인증보다 먼저 돌아야
 * 사전 요청(OPTIONS)이 401로 막히지 않고, 401 응답에도 CORS 헤더가 붙어 콘솔이 읽을 수 있다.
 */
@Configuration
public class WebCorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${platform.cors.allowed-origins}") String[] allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(AllowedOrigins.parse(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        configuration.setAllowedHeaders(List.of("Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
