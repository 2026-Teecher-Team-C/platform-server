package com.teecherteamc.platform.config;

import com.teecherteamc.platform.admin.application.AuthDisabledAdmin;
import com.teecherteamc.platform.admin.application.CurrentAdminUseCase;
import com.teecherteamc.platform.admin.domain.AdminRole;
import com.teecherteamc.platform.admin.interfaces.AdminAuthentication;
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
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
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
 * (nginx가 actuator를 바깥에 노출하지 않는다). 나머지는 로그인이 필요하고 역할별로 나뉜다: 내 정보·로그아웃은 모든
 * 역할, 표에 있는 조회 API는 ADMIN·ANALYST, 그 밖은 ADMIN만이다(docs/auth-policy.md 9장).
 *
 * <p>platform.auth.enabled(AUTH_ENABLED)가 false면 로컬 개발용으로 전부 열고, access 토큰 없는 요청을
 * 초기 관리자로 처리한다(AuthDisabledAdmin). 기본값은 true이고 운영에서는 끄지 않는다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver,
            @Value("${platform.auth.enabled:true}") boolean authEnabled,
            ObjectProvider<AuthDisabledAdmin> authDisabledAdmin,
            CurrentAdminUseCase currentAdminUseCase) throws Exception {
        ErrorResponseSecurityHandler errorHandler = new ErrorResponseSecurityHandler(handlerExceptionResolver);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                // 로그아웃은 AdminAuthController가 refresh 토큰 폐기·감사 로그와 함께 처리한다.
                .logout(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new AdminAuthentication(currentAdminUseCase)))
                        // 토큰이 없거나 틀리거나 만료돼도 공통 ErrorResponse 401로 내린다.
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler));

        if (!authEnabled) {
            // AUTH_ENABLED=false(로컬 개발 전용): 전부 연다. access 토큰이 없으면 행위자는 초기 관리자다.
            http
                    .addFilterAfter(new AuthDisabledAuthenticationFilter(authDisabledAdmin.getObject()),
                            BearerTokenAuthenticationFilter.class)
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
            return http.build();
        }

        // 규칙은 위에서부터 처음 맞는 하나만 적용된다. 권한표는 docs/auth-policy.md 9장이다.
        // 표에서 ANALYST도 되는 읽기 API만 아래에 적고, 나머지(쓰기·감사 로그·화이트리스트·표에 없는 새 API)는
        // 맨 끝 anyRequest()가 ADMIN만 통과시킨다. 규칙을 빠뜨린 API는 열리지 않고 막히는 쪽으로 실패한다.
        String admin = AdminRole.ADMIN.name();
        String analyst = AdminRole.ANALYST.name();
        http.authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/admin/auth/login", "/api/admin/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/admin/download-events").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                .permitAll()
                // 로그인한 모든 역할(USER 포함): 내 정보·로그아웃
                .requestMatchers(HttpMethod.GET, "/api/admin/auth/me").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/admin/auth/logout").authenticated()
                // ADMIN·ANALYST: 조회
                .requestMatchers(HttpMethod.GET,
                        "/api/admin/download-events/*",
                        "/api/admin/events/stream",
                        "/api/admin/quarantine", "/api/admin/quarantine/*",
                        "/api/admin/blacklist",
                        "/api/admin/bypass-domains",
                        "/api/admin/rulesets", "/api/admin/rulesets/*/rules",
                        "/api/admin/agents",
                        "/api/admin/dashboard/unacknowledged-blocks").hasAnyRole(admin, analyst)
                // 그 밖의 모든 요청은 ADMIN만
                .anyRequest().hasRole(admin));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
