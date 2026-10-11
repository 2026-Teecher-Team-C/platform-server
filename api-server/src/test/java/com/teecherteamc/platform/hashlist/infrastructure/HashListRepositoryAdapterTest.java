package com.teecherteamc.platform.hashlist.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntryRepository;
import com.teecherteamc.platform.hashlist.domain.EntrySource;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.Severity;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * hashlist 인프라 계층 검증.
 * 진짜 PostgreSQL 컨테이너에 Flyway(V1__init.sql)가 적용된 상태에서
 * 어댑터 -> JPA 리포지토리 -> @Query 가 의도대로 동작하는지 확인한다.
 * 각 테스트는 트랜잭션으로 감싸져 있어 끝나면 롤백된다.
 */
@SpringBootTest
@Testcontainers
@Transactional
class HashListRepositoryAdapterTest {

    // 이 테스트 클래스 전용 DB 컨테이너. @ServiceConnection이 접속 정보를 앱에 자동으로 연결한다.
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired BlacklistEntryRepository blacklist;
    @Autowired WhitelistEntryRepository whitelist;
    @Autowired JdbcTemplate jdbc;

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    UUID admin;

    @BeforeEach
    void insertAdmin() {
        // hash_whitelist.added_by 가 admin_users(admin_id) FK 이므로 관리자 1명이 먼저 필요하다
        admin = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?::uuid, ?, 'x')",
                admin.toString(), "tester-" + admin);
    }

    // ── 블랙리스트 ─────────────────────────────────────────

    @Test
    void 블랙리스트는_활성일_때만_걸린다() {
        HashKey key = HashKey.sha256("a".repeat(64));
        blacklist.save(BlacklistEntry.register(
                key, "테스트 악성", Severity.HIGH, EntrySource.MANUAL, null, NOW));

        assertThat(blacklist.existsActive(key)).isTrue();
    }

    @Test
    void 블랙리스트를_비활성화하면_안_걸리지만_행은_남는다() {
        HashKey key = HashKey.sha256("b".repeat(64));
        blacklist.save(BlacklistEntry.register(
                key, "테스트 악성", Severity.HIGH, EntrySource.MANUAL, null, NOW));

        BlacklistEntry entry = blacklist.findByKey(key).orElseThrow();
        entry.deactivate(NOW.plusSeconds(60));
        blacklist.save(entry);

        assertThat(blacklist.existsActive(key)).isFalse();
        // 삭제가 아니라 비활성화이므로 이력은 남아 있다
        assertThat(blacklist.findByKey(key)).isPresent();
        assertThat(blacklist.findByKey(key).orElseThrow().getDeactivatedAt()).isNotNull();
    }

    @Test
    void 등록하지_않은_해시는_블랙리스트에_안_걸린다() {
        assertThat(blacklist.existsActive(HashKey.sha256("c".repeat(64)))).isFalse();
    }

    @Test
    void 대문자와_공백이_섞인_해시로_조회해도_같은_항목을_찾는다() {
        blacklist.save(BlacklistEntry.register(
                HashKey.sha256("d".repeat(64)), "정규화 확인", Severity.LOW, EntrySource.IMPORT, null, NOW));

        HashKey upper = HashKey.sha256("  " + "D".repeat(64) + " ");
        assertThat(blacklist.existsActive(upper)).isTrue();
    }

    // ── 화이트리스트 ───────────────────────────────────────

    @Test
    void 만료일이_없는_화이트리스트는_계속_유효하다() {
        HashKey key = HashKey.sha256("e".repeat(64));
        whitelist.save(WhitelistEntry.register(key, "무기한 예외", admin, null, null, NOW));

        assertThat(whitelist.existsEffective(key, NOW)).isTrue();
        assertThat(whitelist.existsEffective(key, NOW.plus(3650, ChronoUnit.DAYS))).isTrue();
    }

    @Test
    void 화이트리스트는_만료_시각이_지나면_유효하지_않다() {
        HashKey key = HashKey.sha256("f".repeat(64));
        Instant expiresAt = NOW.plus(1, ChronoUnit.HOURS);
        whitelist.save(WhitelistEntry.register(key, "임시 예외", admin, null, expiresAt, NOW));

        assertThat(whitelist.existsEffective(key, NOW)).isTrue();                              // 만료 전
        assertThat(whitelist.existsEffective(key, expiresAt)).isFalse();                       // 만료 시각 정각
        assertThat(whitelist.existsEffective(key, expiresAt.plusSeconds(1))).isFalse();        // 만료 후
    }

    @Test
    void 화이트리스트를_비활성화하면_유효하지_않지만_행은_남는다() {
        HashKey key = HashKey.sha256("1".repeat(64));
        whitelist.save(WhitelistEntry.register(key, "예외", admin, null, null, NOW));

        WhitelistEntry entry = whitelist.findByKey(key).orElseThrow();
        entry.deactivate();
        whitelist.save(entry);

        assertThat(whitelist.existsEffective(key, NOW)).isFalse();
        assertThat(whitelist.findByKey(key)).isPresent();
    }

    @Test
    void 등록하지_않은_해시는_화이트리스트에_안_걸린다() {
        assertThat(whitelist.existsEffective(HashKey.sha256("2".repeat(64)), NOW)).isFalse();
    }
}