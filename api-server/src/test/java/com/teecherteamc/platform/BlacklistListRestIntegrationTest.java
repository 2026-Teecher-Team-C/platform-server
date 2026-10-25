package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GET /api/admin/blacklist. 로그인이 필요한 경로라서 AdminApiClient로 로그인해서 요청한다.
 * 같은 DB를 다른 테스트도 쓰므로, 테스트마다 해시 앞부분(prefix)을 따로 정하고 그 prefix로 시작하는 행만 심어서
 * 요청도 hashValue=prefix로 거른다. 그러면 다른 테스트의 행이 섞이지 않는다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class BlacklistListRestIntegrationTest {

    private static final String URL = "/api/admin/blacklist";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminApiClient adminApi;

    private static String randomHex() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");   // 64자
    }

    // 이 테스트만의 해시 앞부분(16자)
    private static String newPrefix() {
        return randomHex().substring(0, 16);
    }

    // prefix로 시작하는 64자 해시
    private static String hashWith(String prefix) {
        return prefix + randomHex().substring(16);
    }

    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private ResponseEntity<Map<String, Object>> get(String query) {
        return adminApi.getForEntity(URL + "?" + query, mapType());
    }

    private static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("content");
    }

    private void seed(String hash, String severity, String source, UUID addedBy,
                      boolean active, Instant createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO hash_blacklist
                    (hash_type, hash_value, reason, severity, source, added_by, is_active, created_at, deactivated_at)
                VALUES ('SHA256', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                hash, "사유-" + hash.substring(0, 6), severity, source, addedBy, active,
                Timestamp.from(createdAt), active ? null : Timestamp.from(createdAt));
    }

    @Test
    void 기본은_활성_항목만_최신순으로_주고_필드와_페이지_정보를_채운다() {
        String prefix = newPrefix();
        String older = hashWith(prefix);
        String newer = hashWith(prefix);
        String released = hashWith(prefix);
        seed(older, "HIGH", "MANUAL", adminApi.adminId(), true, Instant.parse("2026-10-20T01:00:00Z"));
        seed(newer, "HIGH", "MANUAL", adminApi.adminId(), true, Instant.parse("2026-10-20T02:00:00Z"));
        seed(released, "HIGH", "MANUAL", adminApi.adminId(), false, Instant.parse("2026-10-20T03:00:00Z"));

        ResponseEntity<Map<String, Object>> response = get("hashValue=" + prefix);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = content(response);
        assertThat(items).hasSize(2);   // 해제된 항목은 기본에서 빠진다
        assertThat(items.get(0).get("hashValue")).isEqualTo(newer);   // 최신순
        assertThat(items.get(1).get("hashValue")).isEqualTo(older);
        Map<String, Object> first = items.get(0);
        assertThat(first.get("hashType")).isEqualTo("SHA256");
        assertThat(first.get("severity")).isEqualTo("HIGH");
        assertThat(first.get("source")).isEqualTo("MANUAL");
        assertThat(first.get("isActive")).isEqualTo(true);
        assertThat(first.get("deactivatedAt")).isNull();
        assertThat(Instant.parse((String) first.get("createdAt"))).isEqualTo(Instant.parse("2026-10-20T02:00:00Z"));
        Map<String, Object> addedBy = (Map<String, Object>) first.get("addedBy");
        assertThat(addedBy.get("adminId")).isEqualTo(adminApi.adminId().toString());
        assertThat(addedBy.get("username")).isEqualTo(adminApi.username());
        Map<String, Object> body = response.getBody();
        assertThat(body.get("page")).isEqualTo(0);
        assertThat(body.get("size")).isEqualTo(20);
        assertThat(body.get("totalElements")).isEqualTo(2);
        assertThat(body.get("totalPages")).isEqualTo(1);
        assertThat(body).doesNotContainKeys("items", "nextCursor");
    }

    @Test
    void isActive가_false면_해제된_항목만_준다() {
        String prefix = newPrefix();
        String active = hashWith(prefix);
        String released = hashWith(prefix);
        seed(active, "HIGH", "MANUAL", adminApi.adminId(), true, Instant.parse("2026-10-20T01:00:00Z"));
        seed(released, "HIGH", "MANUAL", adminApi.adminId(), false, Instant.parse("2026-10-20T02:00:00Z"));

        ResponseEntity<Map<String, Object>> response = get("hashValue=" + prefix + "&isActive=false");

        List<Map<String, Object>> items = content(response);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("hashValue")).isEqualTo(released);
        assertThat(items.get(0).get("isActive")).isEqualTo(false);
        assertThat(items.get(0).get("deactivatedAt")).isNotNull();
    }

    @Test
    void severity로_거른다() {
        String prefix = newPrefix();
        String high = hashWith(prefix);
        String low = hashWith(prefix);
        seed(high, "HIGH", "MANUAL", adminApi.adminId(), true, Instant.parse("2026-10-20T01:00:00Z"));
        seed(low, "LOW", "MANUAL", adminApi.adminId(), true, Instant.parse("2026-10-20T02:00:00Z"));

        List<Map<String, Object>> items = content(get("hashValue=" + prefix + "&severity=LOW"));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("hashValue")).isEqualTo(low);
    }

    @Test
    void source로_거르고_등록자가_없는_항목은_addedBy가_null이다() {
        String prefix = newPrefix();
        String manual = hashWith(prefix);
        String imported = hashWith(prefix);
        seed(manual, "HIGH", "MANUAL", adminApi.adminId(), true, Instant.parse("2026-10-20T01:00:00Z"));
        seed(imported, "HIGH", "IMPORT", null, true, Instant.parse("2026-10-20T02:00:00Z"));

        List<Map<String, Object>> items = content(get("hashValue=" + prefix + "&source=IMPORT"));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("hashValue")).isEqualTo(imported);
        assertThat(items.get(0).get("addedBy")).isNull();
    }

    @Test
    void hashValue는_앞부분_일치이고_대문자로_넣어도_소문자로_바꿔_찾는다() {
        String prefix = newPrefix();
        String target = prefix + "aaaaaaaa" + randomHex().substring(24);
        String other = prefix + "bbbbbbbb" + randomHex().substring(24);
        seed(target, "HIGH", "MANUAL", adminApi.adminId(), true, Instant.parse("2026-10-20T01:00:00Z"));
        seed(other, "HIGH", "MANUAL", adminApi.adminId(), true, Instant.parse("2026-10-20T02:00:00Z"));

        // prefix + 구분하는 앞 8자를 대문자로
        List<Map<String, Object>> items = content(get("hashValue=" + (prefix + "aaaaaaaa").toUpperCase()));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("hashValue")).isEqualTo(target);
    }

    @Test
    void page와_size로_나눠서_준다() {
        String prefix = newPrefix();
        seed(hashWith(prefix), "HIGH", "MANUAL", adminApi.adminId(), true, Instant.parse("2026-10-20T01:00:00Z"));
        seed(hashWith(prefix), "HIGH", "MANUAL", adminApi.adminId(), true, Instant.parse("2026-10-20T02:00:00Z"));
        seed(hashWith(prefix), "HIGH", "MANUAL", adminApi.adminId(), true, Instant.parse("2026-10-20T03:00:00Z"));

        ResponseEntity<Map<String, Object>> firstPage = get("hashValue=" + prefix + "&page=0&size=2");
        ResponseEntity<Map<String, Object>> secondPage = get("hashValue=" + prefix + "&page=1&size=2");

        assertThat(content(firstPage)).hasSize(2);
        assertThat(firstPage.getBody().get("totalElements")).isEqualTo(3);
        assertThat(firstPage.getBody().get("totalPages")).isEqualTo(2);
        assertThat(content(secondPage)).hasSize(1);
        assertThat(secondPage.getBody().get("page")).isEqualTo(1);
    }

    @Test
    void 잘못된_파라미터는_400_INVALID_INPUT이다() {
        List<String> badQueries = List.of(
                "hashValue=not-hex",
                "hashValue=" + "a".repeat(65),
                "severity=NOPE",
                "source=NOPE",
                "isActive=abc",
                "page=-1",
                "size=0",
                "size=201");

        for (String query : badQueries) {
            ResponseEntity<Map<String, Object>> response = get(query);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).as(query).isEqualTo("INVALID_INPUT");
        }
    }

    @Test
    void 빈_문자열_필터는_걸지_않은_것과_같다() {
        ResponseEntity<Map<String, Object>> response = get("severity=&source=&hashValue=");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 로그인하지_않으면_401이다() {
        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(URL, mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }
}