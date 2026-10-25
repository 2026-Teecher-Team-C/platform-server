package com.teecherteamc.platform.config;

import com.teecherteamc.platform.admin.interfaces.AdminAuthentication;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * REST(8080) 보안 설정. 관리 콘솔은 {@code Authorization: Bearer <access 토큰>}으로 인증한다(docs/auth-policy.md).
 * gRPC(9090, 에이전트)는 서블릿 필터를 타지 않으므로 이 설정과 상관없다 — 에이전트 인증은
 * AgentAuthServerInterceptor가 한다.
 *
 * <p>세션을 만들지 않는다(STATELESS). 쿠키로 인증하지 않으니 브라우저가 자격 증명을 자동으로 싣는 일이 없어
 * CSRF 방어가 필요 없다.
 *
 * <p>로그인 없이 열린 곳: 로그인·refresh API, 시연용 다운로드 이벤트 목록(시연이 끝나면 막는다), 헬스체크·Prometheus
 * (nginx가 actuator를 바깥에 노출하지 않는다). 나머지는 전부 로그인이 필요하다. 역할별 권한은 아직 나누지 않는다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver)
            throws Exception {
        ErrorResponseSecurityHandler errorHandler = new ErrorResponseSecurityHandler(handlerExceptionResolver);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                // 로그아웃은 AdminAuthController가 refresh 토큰 폐기·감사 로그와 함께 처리한다.
                .logout(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new AdminAuthentication()))
                        // 토큰이 없거나 틀리거나 만료돼도 공통 ErrorResponse 401로 내린다.
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login", "/api/admin/auth/refresh")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/admin/download-events").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
