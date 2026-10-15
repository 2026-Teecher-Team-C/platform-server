package com.teecherteamc.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 관리 콘솔(Vercel·로컬 vite)이 브라우저에서 `/api/**`를 직접 호출할 수 있게 출처를 허용한다.
 * gRPC(에이전트)와 actuator는 브라우저가 부르지 않으므로 대상이 아니다.
 *
 * <p>인증은 Bearer 토큰이라 쿠키를 보내지 않는다(allowCredentials 끔). Spring Security를 붙이면
 * {@code http.cors()}를 켜야 이 설정이 보안 필터보다 먼저 적용된다.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebCorsConfig(@Value("${platform.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .allowedHeaders("Authorization", "Content-Type");
    }
}
