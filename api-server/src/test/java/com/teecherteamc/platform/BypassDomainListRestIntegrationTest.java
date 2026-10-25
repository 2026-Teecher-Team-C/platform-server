package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// 로그인이 필요한 API라 AdminApiClient로 로그인한 access 토큰으로 요청한다. VIEWER 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class BypassDomainListRestIntegrationTest {

    private static final String URL = "/api/admin/bypass-domains";

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // bypass_domains를 가리키는 FK가 없고 이 테이블을 쓰는 다른 테스트도 아직 없어서, totalElements를
    // 정확히 확인하려고 매번 비운다.
    @BeforeEach
    void 테이블을_비운다() {
        jdbcTemplate.update("DELETE FROM bypass_domains");
    }

    private UUID seed(String host, String category, boolean active, Instant createdAt) {
        UUID bypassId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO bypass_domains (bypass_id, domain_pattern, category, reason, is_active, created_at)
                VALUES (?, ?, ?, 'test reason', ?, ?)
                """,
                bypassId, host, category, active, Timestamp.from(createdAt));
        return bypassId;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> get(String query) {
        return adminClient.getForEntity(URL + query, (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("content");
    }

    private static List<Object> hosts(ResponseEntity<Map<String, Object>> response) {
        return content(response).stream().map(row -> row.get("host")).toList();
    }

    @Test
    void 기본_호출은_활성만_내려주고_PageResponse_모양이다() {
        seed("active.example.com", "PINNED", true, Instant.parse("2026-10-23T00:00:00Z"));
        seed("inactive.example.com", "PINNED", false, Instant.parse("2026-10-23T00:00:00Z"));

        ResponseEntity<Map<String, Object>> response = get("");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("content", "page", "size", "totalElements", "totalPages");
        assertThat(response.getBody().get("page")).isEqualTo(0);
        assertThat(response.getBody().get("size")).isEqualTo(20);
        assertThat(response.getBody().get("totalElements")).isEqualTo(1);
        assertThat(hosts(response)).containsExactly("active.example.com");
    }

    @Test
    void 응답_항목은_콘솔_필드_이름을_쓴다() {
        UUID bypassId = seed("fields.example.com", "SECURITY_UPDATE", true, Instant.parse("2026-10-23T00:00:00Z"));

        Map<String, Object> item = content(get("")).get(0);

        assertThat(item.get("bypassId")).isEqualTo(bypassId.toString());
        assertThat(item.get("host")).isEqualTo("fields.example.com");
        assertThat(item.get("category")).isEqualTo("SECURITY_UPDATE");
        assertThat(item.get("reason")).isEqualTo("test reason");
        assertThat(item.get("isActive")).isEqualTo(true);
        assertThat(item.get("createdAt")).isNotNull();
        assertThat(item).doesNotContainKeys("active", "domainPattern");
    }

    @Test
    void isActive_false면_비활성만_내려준다() {
        seed("active.example.com", "PINNED", true, Instant.now());
        seed("inactive.example.com", "PINNED", false, Instant.now());

        ResponseEntity<Map<String, Object>> response = get("?isActive=false");

        assertThat(hosts(response)).containsExactly("inactive.example.com");
        assertThat(content(response).get(0).get("isActive")).isEqualTo(false);
    }

    @Test
    void category로_필터링된다() {
        seed("pinned.example.com", "PINNED", true, Instant.now());
        seed("update.example.com", "SECURITY_UPDATE", true, Instant.now());

        assertThat(hosts(get("?category=PINNED"))).containsExactly("pinned.example.com");
        assertThat(hosts(get("?category=SECURITY_UPDATE"))).containsExactly("update.example.com");
    }

    @Test
    void host는_대소문자를_무시한_부분_일치로_찾는다() {
        seed("Update.Microsoft.com", "SECURITY_UPDATE", true, Instant.now());
        seed("pinned.example.com", "PINNED", true, Instant.now());

        assertThat(hosts(get("?host=MICROSOFT"))).containsExactly("Update.Microsoft.com");
    }

    @Test
    void host의_퍼센트와_밑줄은_와일드카드가_아니라_문자로_찾는다() {
        seed("a_b.example.com", "PINNED", true, Instant.now());
        seed("axb.example.com", "PINNED", true, Instant.now());

        assertThat(hosts(get("?host=a_b"))).containsExactly("a_b.example.com");
        assertThat(content(get("?host=%25"))).isEmpty();
    }

    @Test
    void 등록_시각_내림차순으로_정렬된다() {
        seed("old.example.com", "PINNED", true, Instant.parse("2026-10-01T00:00:00Z"));
        seed("new.example.com", "PINNED", true, Instant.parse("2026-10-23T00:00:00Z"));
        seed("mid.example.com", "PINNED", true, Instant.parse("2026-10-10T00:00:00Z"));

        assertThat(hosts(get(""))).containsExactly("new.example.com", "mid.example.com", "old.example.com");
    }

    @Test
    void 페이지네이션이_동작한다() {
        for (int i = 0; i < 5; i++) {
            seed("host" + i + ".example.com", "PINNED", true, Instant.parse("2026-10-23T00:00:00Z").plusSeconds(i));
        }

        ResponseEntity<Map<String, Object>> response = get("?page=1&size=2");

        assertThat(content(response)).hasSize(2);
        assertThat(response.getBody().get("page")).isEqualTo(1);
        assertThat(response.getBody().get("size")).isEqualTo(2);
        assertThat(response.getBody().get("totalElements")).isEqualTo(5);
        assertThat(response.getBody().get("totalPages")).isEqualTo(3);
        assertThat(hosts(response)).containsExactly("host2.example.com", "host1.example.com");
    }

    @Test
    void 범위를_벗어난_page는_빈_목록이다() {
        seed("only.example.com", "PINNED", true, Instant.now());

        ResponseEntity<Map<String, Object>> response = get("?page=5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content(response)).isEmpty();
        assertThat(response.getBody().get("totalElements")).isEqualTo(1);
    }

    @Test
    void 잘못된_파라미터는_400이다() {
        for (String query : List.of(
                "?category=NOPE", "?page=-1", "?size=0", "?size=201", "?page=abc", "?isActive=maybe")) {
            ResponseEntity<Map<String, Object>> response = get(query);

            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).as(query).isEqualTo("INVALID_INPUT");
        }
    }
}
