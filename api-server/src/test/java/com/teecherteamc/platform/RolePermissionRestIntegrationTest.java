package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.server.PathContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 역할별 권한표(docs/auth-policy.md 9장)를 그대로 옮긴 테스트. API 하나를 ADMIN·ANALYST·USER 계정으로 각각 불러서
 * 표대로 막히는지 확인한다.
 *
 * <p>권한 검사는 컨트롤러가 실행되기 전에 일어나므로 올바른 본문이나 존재하는 데이터가 필요 없다. 그래서 "성공"이 아니라
 * 권한만 본다: 허용된 역할은 401도 403도 아니면 통과한 것(본문이 틀려 400, 없는 id라 404가 나도 된다), 막혀야 하는
 * 역할은 403과 {@code FORBIDDEN} 코드여야 한다. 각 API의 실제 동작은 기능별 통합 테스트가 검증한다.
 *
 * <p>서버는 토큰의 role을 믿지 않고 요청마다 admin_users의 role을 읽는다. 그래서 계정을 role과 함께 DB에 넣고, 토큰은
 * 로그인 대신 JwtEncoder로 직접 만든다(로그인은 AdminAuthRestIntegrationTest가 검증한다).
 *
 * <p>표에 행을 추가할 때는 docs/auth-policy.md 9장도 같이 고친다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RolePermissionRestIntegrationTest {

    private static final String SOME_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String SOME_SHA256 = "0".repeat(64);

    // 로그인 없이 열린 API(SecurityConfig의 permitAll). 역할 표에 넣지 않는다.
    private static final Set<String> PUBLIC_WITHOUT_LOGIN = Set.of(
            "POST /api/admin/auth/login",
            "POST /api/admin/auth/refresh",
            "GET /api/admin/download-events");  // 시연용, 시연이 끝나면 막고 표로 옮긴다

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, String> tokens = new HashMap<>();

    @BeforeAll
    void 역할별_계정과_토큰을_만든다() {
        for (String role : List.of("ADMIN", "ANALYST", "USER")) {
            UUID adminId = UUID.randomUUID();
            String username = "role-test-" + role.toLowerCase() + "-" + adminId;
            jdbcTemplate.update(
                    """
                    INSERT INTO admin_users (admin_id, username, password_hash, role, is_active)
                    VALUES (?, ?, 'unused-hash', ?, true)
                    """,
                    adminId, username, role);
            tokens.put(role, accessToken(adminId, username));
        }
    }

    @ParameterizedTest(name = "{0} {1} {2} → 허용={3}")
    @MethodSource("permissionTable")
    void 역할별로_권한표대로_허용하거나_403을_낸다(String role, String method, String path, boolean allowed)
            throws Exception {
        HttpResponse<InputStream> response = send(method, path, tokens.get(role));

        // 스트림(SSE)은 본문이 끝나지 않으므로 상태 코드만 읽고 바로 닫는다.
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (allowed) {
                assertThat(status).as("%s이(가) %s %s를 호출", role, method, path).isNotIn(401, 403);
            } else {
                assertThat(status).as("%s이(가) %s %s를 호출", role, method, path).isEqualTo(403);
                assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).contains("FORBIDDEN");
            }
        }
    }

    /**
     * 누락 감시: 앱에 등록된 모든 /api/admin API가 권한표에 있어야 한다. 새 API를 만들고 표를 안 고치면 여기서 실패하고,
     * 빠진 API 이름을 알려 준다. SecurityConfig는 표에 없는 API를 ADMIN만 통과시키므로(안전한 기본값) 실제로 열리지는
     * 않지만, 어느 역할에게 줄지 정하지 않은 채 남지 않게 한다.
     */
    @Test
    void 등록된_모든_관리_API는_권한표에_있다() {
        Set<String> missing = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/admin/")) {
                    continue;
                }
                PathPattern parsed = PathPatternParser.defaultInstance.parse(pattern);
                // @RequestMapping에 메서드를 안 적었으면 모든 메서드다. 그때는 경로만 맞으면 표에 있다고 본다.
                Set<String> verbs = methods.isEmpty()
                        ? Set.of("ANY")
                        : methods.stream().map(Enum::name).collect(Collectors.toSet());
                for (String verb : verbs) {
                    boolean isPublic = PUBLIC_WITHOUT_LOGIN.contains(verb + " " + pattern);
                    boolean inTable = TABLE.stream().anyMatch(row ->
                            (verb.equals("ANY") || row.method().equals(verb))
                                    && parsed.matches(PathContainer.parsePath(row.path())));
                    if (!isPublic && !inTable) {
                        missing.add(verb + " " + pattern);
                    }
                }
            }
        }

        assertThat(missing)
                .as("권한표(TABLE), SecurityConfig, docs/auth-policy.md 9장에 추가가 필요한 API")
                .isEmpty();
    }

    /** 권한표 한 행: 메서드, 경로, ADMIN·ANALYST·USER 각각 허용되는가. */
    private record Row(String method, String path, boolean admin, boolean analyst, boolean user) {
    }

    private static final List<Row> TABLE = List.of(
            // TLSH 조회/관리도 최신 ADMIN 전용 정책을 유지한다.
            new Row("GET", "/api/admin/samples/" + SOME_SHA256, true, false, false),
            new Row("POST", "/api/admin/file-fingerprints/import", true, false, false),
            // 이벤트 조회 (목록 GET은 시연용으로 로그인 없이 열려 있어 역할 표에 없다)
            new Row("GET", "/api/admin/download-events/" + SOME_UUID, true, true, false),
            new Row("GET", "/api/admin/events/stream", true, true, false),
            new Row("POST", "/api/admin/download-events/" + SOME_UUID + "/acknowledge", true, false, false),
            new Row("GET", "/api/admin/dashboard/unacknowledged-blocks", true, true, false),
            // 격리
            new Row("GET", "/api/admin/quarantine", true, true, false),
            new Row("GET", "/api/admin/quarantine/" + SOME_UUID, true, true, false),
            new Row("POST", "/api/admin/quarantine/" + SOME_UUID + "/restore", true, false, false),
            // 블랙리스트
            new Row("GET", "/api/admin/blacklist", true, true, false),
            new Row("POST", "/api/admin/blacklist", true, false, false),
            new Row("PATCH", "/api/admin/blacklist/" + SOME_SHA256, true, false, false),
            new Row("DELETE", "/api/admin/blacklist/" + SOME_SHA256, true, false, false),
            // 화이트리스트 (읽기도 ADMIN만)
            new Row("GET", "/api/admin/whitelist", true, false, false),
            new Row("POST", "/api/admin/whitelist", true, false, false),
            new Row("PATCH", "/api/admin/whitelist/" + SOME_SHA256, true, false, false),
            new Row("DELETE", "/api/admin/whitelist/" + SOME_SHA256, true, false, false),
            // 우회 도메인
            new Row("GET", "/api/admin/bypass-domains", true, true, false),
            new Row("POST", "/api/admin/bypass-domains", true, false, false),
            new Row("DELETE", "/api/admin/bypass-domains/" + SOME_UUID, true, false, false),
            // 룰셋
            new Row("GET", "/api/admin/rulesets", true, true, false),
            new Row("GET", "/api/admin/rulesets/1/rules", true, true, false),
            new Row("POST", "/api/admin/rulesets", true, false, false),
            new Row("POST", "/api/admin/rulesets/1/activate", true, false, false),
            new Row("PATCH", "/api/admin/rulesets/1/rules/some-rule", true, false, false),
            // 에이전트
            new Row("GET", "/api/admin/agents", true, true, false),
            new Row("POST", "/api/admin/agents/enrollment-tokens", true, false, false),
            // 감사 로그 (읽기도 ADMIN만)
            new Row("GET", "/api/admin/audit-logs", true, false, false),
            // 내 정보·로그아웃: 로그인한 모든 역할
            new Row("GET", "/api/admin/auth/me", true, true, true),
            new Row("POST", "/api/admin/auth/logout", true, true, true));

    private static Stream<Arguments> permissionTable() {
        return TABLE.stream().flatMap(row -> Stream.of(
                Arguments.of("ADMIN", row.method(), row.path(), row.admin()),
                Arguments.of("ANALYST", row.method(), row.path(), row.analyst()),
                Arguments.of("USER", row.method(), row.path(), row.user())));
    }

    private String accessToken(UUID adminId, String username) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(adminId.toString())
                .claim("username", username)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private HttpResponse<InputStream> send(String method, String path, String token) throws Exception {
        boolean hasBody = method.equals("POST") || method.equals("PATCH");
        BodyPublisher bodyPublisher = hasBody ? BodyPublishers.ofString("{}") : BodyPublishers.noBody();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .method(method, bodyPublisher);
        if (hasBody) {
            request.header("Content-Type", "application/json");
        }
        return httpClient.send(request.build(), BodyHandlers.ofInputStream());
    }
}
