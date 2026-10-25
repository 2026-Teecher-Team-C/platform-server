package com.teecherteamc.platform.hashlist.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntryRepository;
import com.teecherteamc.platform.hashlist.domain.EntrySource;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.Severity;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 * 어댑터 -> JPA 리포지토리 -> 테이블 매핑이 의도대로 동작하는지 확인한다.
 * 각 테스트는 트랜잭션으로 감싸져 있어 끝나면 롤백된다.
 *
 * 해시 판정(조회 사슬)은 lookup이 전용 쿼리로 하므로 여기서 다루지 않는다.
 * 여기서는 "저장하고 다시 읽으면 값이 그대로인가"와 엔티티의 판정 규칙을 확인한다.
 */
// 공용 TestcontainersConfiguration을 쓰지 않아서 기동에 필요한 JWT 서명 키를 여기서 넣는다.
@SpringBootTest(properties = "platform.auth.jwt.secret=hash-list-repository-test-secret-at-least-32-bytes")
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
    @PersistenceContext EntityManager em;

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    UUID admin;

    @BeforeEach
    void insertAdmin() {
        // hash_whitelist.added_by 가 admin_users(admin_id) FK 이므로 관리자 1명이 먼저 필요하다
        admin = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?::uuid, ?, 'x')",
                admin.toString(), "tester-" + admin);
    }

    /**
     * DB에 실제로 쓰고(flush) JPA 메모리 캐시를 비운다(clear).
     * 이걸 안 하면 findByKey가 DB가 아니라 캐시의 같은 객체를 돌려줘서, 컬럼 매핑이 틀려도 통과해버린다.
     */
    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    // ── 블랙리스트 ─────────────────────────────────────────

    @Test
    void 블랙리스트를_저장하고_다시_읽어도_값이_그대로다() {
        HashKey key = HashKey.sha256("a".repeat(64));
        blacklist.save(BlacklistEntry.register(
                key, "테스트 악성", Severity.HIGH, EntrySource.MANUAL, admin, NOW));
        flushAndClear();

        BlacklistEntry found = blacklist.findByKey(key).orElseThrow();
        assertThat(found.getReason()).isEqualTo("테스트 악성");
        assertThat(found.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(found.getSource()).isEqualTo(EntrySource.MANUAL);
        assertThat(found.getAddedBy()).isEqualTo(admin);
        assertThat(found.isActive()).isTrue();
    }

    @Test
    void 블랙리스트를_비활성화하면_행은_남고_비활성_상태가_된다() {
        HashKey key = HashKey.sha256("b".repeat(64));
        blacklist.save(BlacklistEntry.register(
                key, "테스트 악성", Severity.HIGH, EntrySource.MANUAL, null, NOW));
        flushAndClear();

        BlacklistEntry entry = blacklist.findByKey(key).orElseThrow();
        entry.deactivate(NOW.plusSeconds(60));
        blacklist.save(entry);
        flushAndClear();

        // 삭제가 아니라 비활성화이므로 이력은 남아 있다
        BlacklistEntry found = blacklist.findByKey(key).orElseThrow();
        assertThat(found.isActive()).isFalse();
        assertThat(found.getDeactivatedAt()).isNotNull();
    }

    @Test
    void 등록하지_않은_해시는_블랙리스트에서_찾을_수_없다() {
        assertThat(blacklist.findByKey(HashKey.sha256("c".repeat(64)))).isEmpty();
    }

    @Test
    void 대문자와_공백이_섞인_해시로_조회해도_같은_항목을_찾는다() {
        blacklist.save(BlacklistEntry.register(
                HashKey.sha256("d".repeat(64)), "정규화 확인", Severity.LOW, EntrySource.IMPORT, null, NOW));
        flushAndClear();

        HashKey upper = HashKey.sha256("  " + "D".repeat(64) + " ");
        assertThat(blacklist.findByKey(upper)).isPresent();
    }

    // ── 화이트리스트 ───────────────────────────────────────

    @Test
    void 만료일이_없는_화이트리스트는_다시_읽어도_계속_유효하다() {
        HashKey key = HashKey.sha256("e".repeat(64));
        whitelist.save(WhitelistEntry.register(key, "무기한 예외", admin, null, null, NOW));
        flushAndClear();

        WhitelistEntry found = whitelist.findByKey(key).orElseThrow();
        assertThat(found.getAddedBy()).isEqualTo(admin);
        assertThat(found.getExpiresAt()).isNull();
        assertThat(found.isEffectiveAt(NOW)).isTrue();
        assertThat(found.isEffectiveAt(NOW.plus(3650, ChronoUnit.DAYS))).isTrue();
    }

    @Test
    void 화이트리스트는_만료_시각_정각부터_유효하지_않다() {
        HashKey key = HashKey.sha256("f".repeat(64));
        Instant expiresAt = NOW.plus(1, ChronoUnit.HOURS);
        whitelist.save(WhitelistEntry.register(key, "임시 예외", admin, null, expiresAt, NOW));
        flushAndClear();

        // DB를 거쳐 다시 읽은 만료 시각으로 경계를 확인한다. 이 규칙은 lookup 쿼리도 같아야 한다.
        WhitelistEntry found = whitelist.findByKey(key).orElseThrow();
        assertThat(found.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(found.isEffectiveAt(NOW)).isTrue();                          // 만료 전
        assertThat(found.isEffectiveAt(expiresAt)).isFalse();                   // 만료 시각 정각
        assertThat(found.isEffectiveAt(expiresAt.plusSeconds(1))).isFalse();    // 만료 후
    }

    @Test
    void 화이트리스트를_비활성화하면_행은_남고_더는_유효하지_않다() {
        HashKey key = HashKey.sha256("1".repeat(64));
        whitelist.save(WhitelistEntry.register(key, "예외", admin, null, null, NOW));
        flushAndClear();

        WhitelistEntry entry = whitelist.findByKey(key).orElseThrow();
        entry.deactivate();
        whitelist.save(entry);
        flushAndClear();

        WhitelistEntry found = whitelist.findByKey(key).orElseThrow();
        assertThat(found.isActive()).isFalse();
        assertThat(found.isEffectiveAt(NOW)).isFalse();
    }

    @Test
    void 등록하지_않은_해시는_화이트리스트에서_찾을_수_없다() {
        assertThat(whitelist.findByKey(HashKey.sha256("2".repeat(64)))).isEmpty();
    }
}