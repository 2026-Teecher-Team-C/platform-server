package com.teecherteamc.platform.config;

import com.teecherteamc.platform.admin.application.AuthDisabledAdmin;
import com.teecherteamc.platform.admin.interfaces.AuthDisabledAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * REST(8080) 보안 설정. 관리 콘솔은 서버 세션 쿠키로 로그인한다(docs/auth-policy.md).
 * gRPC(9090, 에이전트)는 서블릿 필터를 타지 않으므로 이 설정과 상관없다 — 에이전트 인증은
 * AgentAuthServerInterceptor가 한다.
 *
 * <p>로그인 없이 열린 곳: 로그인 API, 시연용 다운로드 이벤트 목록(시연이 끝나면 막는다), 헬스체크·Prometheus
 * (nginx가 actuator를 바깥에 노출하지 않는다). 나머지는 전부 로그인이 필요하다. 역할별 권한은 아직 나누지 않는다.
 *
 * <p>platform.auth.enabled(AUTH_ENABLED)가 false면 로컬 개발용으로 전부 열고, 로그인하지 않은 요청을
 * 초기 관리자로 처리한다(AuthDisabledAdmin). 기본값은 true이고 운영에서는 끄지 않는다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            SecurityContextRepository securityContextRepository,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver,
            @Value("${platform.cors.allowed-origins}") String[] allowedOrigins,
            @Value("${platform.auth.enabled:true}") boolean authEnabled,
            ObjectProvider<AuthDisabledAdmin> authDisabledAdmin) throws Exception {
        ErrorResponseSecurityHandler errorHandler = new ErrorResponseSecurityHandler(handlerExceptionResolver);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                // 401 때 원래 요청을 세션에 저장하지 않는다. 로그인하지 않은 요청마다 세션이 생기는 것을 막는다.
                .requestCache(AbstractHttpConfigurer::disable)
                // 로그아웃은 AdminAuthController가 감사 로그와 함께 처리한다.
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler));

        if (!authEnabled) {
            // AUTH_ENABLED=false(로컬 개발 전용): 전부 열고 Origin 검사도 하지 않는다. 행위자는 초기 관리자다.
            http
                    .addFilterAfter(new AuthDisabledAuthenticationFilter(authDisabledAdmin.getObject()),
                            SecurityContextHolderFilter.class)
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
            return http.build();
        }

        http
                // CSRF 토큰 대신 OriginCheckFilter로 막는다. 이유는 그 클래스 주석 참고.
                .addFilterBefore(new OriginCheckFilter(AllowedOrigins.parse(allowedOrigins), errorHandler),
                        CorsFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/admin/download-events").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    /** 로그인 상태는 HTTP 세션에 둔다. AdminSession이 로그인할 때 같은 저장소에 직접 저장한다. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
