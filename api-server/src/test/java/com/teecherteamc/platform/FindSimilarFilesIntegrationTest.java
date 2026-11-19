package com.teecherteamc.platform;

import static com.teecherteamc.platform.TlshFixtures.REFERENCE;
import static com.teecherteamc.platform.TlshFixtures.digestAt;
import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.verdict.application.FindSimilarFiles;
import com.teecherteamc.platform.verdict.domain.FingerprintProvenance;
import com.teecherteamc.platform.verdict.domain.SimilarFile;
import com.teecherteamc.platform.verdict.domain.SimilarFiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 서비스를 스프링 프록시(읽기 전용 트랜잭션)를 거쳐 실제 DB로 확인한다.
 * 같은 DB를 쓰는 다른 테스트가 남긴 지문이 섞이므로 이 테스트가 넣은 행만 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FindSimilarFilesIntegrationTest {

    @Autowired FindSimilarFiles service;
    @Autowired JdbcTemplate jdbc;

    private final List<String> inserted = new ArrayList<>();
    private UUID admin;

    @AfterEach
    void cleanUp() {
        inserted.forEach(sha -> jdbc.update("DELETE FROM file_fingerprints WHERE sha256=?", sha));
        if (admin != null) {
            jdbc.update("DELETE FROM admin_users WHERE admin_id=?", admin);
        }
    }

    private String insertVerified(String tlsh) {
        String sha = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.update("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) "
                + "VALUES (?, ?, 'CALCULATED', 'review', 'VERIFIED_UPLOAD')", sha, tlsh);
        inserted.add(sha);
        return sha;
    }

    private String insertImported(String tlsh) {
        String sha = UUID.randomUUID().toString().replace("-", "").repeat(2);
        if (admin == null) {
            admin = UUID.randomUUID();
            jdbc.update("INSERT INTO admin_users(admin_id, username, password_hash) VALUES (?, ?, 'unused')",
                    admin, "find-similar-" + admin);
        }
        jdbc.update("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance, "
                + "provenance_detail, imported_by) VALUES (?, ?, 'CALCULATED', 'review', 'ADMIN_IMPORT', 'fixture', ?)",
                sha, tlsh, admin);
        inserted.add(sha);
        return sha;
    }

    private List<SimilarFile> mine(SimilarFiles result) {
        return result.files().stream().filter(f -> inserted.contains(f.sha256())).toList();
    }

    @Test
    void sha로_찾으면_기준_자신은_빠지고_가까운_순으로_나온다() {
        String reference = insertVerified(REFERENCE);
        String at31 = insertVerified(digestAt(31));
        String at30 = insertVerified(digestAt(30));
        insertVerified(digestAt(71));

        SimilarFiles result = service.bySha256(reference, 70, 1000);

        assertThat(result.status()).isEqualTo(SimilarFiles.Status.OK);
        assertThat(result.referenceSha256()).isEqualTo(reference);
        assertThat(mine(result)).containsExactly(new SimilarFile(at30, 30), new SimilarFile(at31, 31));
        assertThat(result.files()).extracting(SimilarFile::sha256).doesNotContain(reference);
    }

    @Test
    void 지문이_없는_sha는_NO_REFERENCE_FINGERPRINT를_돌려준다() {
        SimilarFiles result = service.bySha256(UUID.randomUUID().toString().replace("-", "").repeat(2), 70, 10);

        assertThat(result.status()).isEqualTo(SimilarFiles.Status.NO_REFERENCE_FINGERPRINT);
    }

    @Test
    void 해석할_수_없는_TLSH는_예외가_아니라_상태로_돌려주고_트랜잭션도_정상_종료한다() {
        // 서비스는 @Transactional이라 어댑터 예외가 트랜잭션을 rollback-only로 만들면 여기서 UnexpectedRollbackException이 난다.
        SimilarFiles result = service.byTlsh("T1" + "Z".repeat(70), 30, 10);

        assertThat(result.status()).isEqualTo(SimilarFiles.Status.INVALID_TLSH);
    }

    @Test
    void 기본_출처는_검증된_업로드만이고_ADMIN_IMPORT는_인자로_고른다() {
        String verified = insertVerified(digestAt(10));
        String imported = insertImported(digestAt(10));

        assertThat(mine(service.byTlsh(REFERENCE, 70, 1000)))
                .extracting(SimilarFile::sha256).containsExactly(verified);
        assertThat(mine(service.byTlsh(REFERENCE, 70, 1000, Set.of(FingerprintProvenance.ADMIN_IMPORT), null)))
                .extracting(SimilarFile::sha256).containsExactly(imported);
    }

    @Test
    void limit을_넘으면_가까운_것만_남기고_truncated로_알린다() {
        insertVerified(digestAt(10));
        insertVerified(digestAt(30));
        insertVerified(digestAt(31));
        insertVerified(digestAt(45));

        // 다른 테스트가 남긴 지문이 섞여 있어도 이웃이 2개보다 많은 것은 확실하다. 정확한 선택은 수집기 단위 테스트가 본다.
        SimilarFiles result = service.byTlsh(REFERENCE, 70, 2);

        assertThat(result.truncated()).isTrue();
        assertThat(result.files()).hasSize(2);
        assertThat(result.files()).extracting(SimilarFile::distance).isSorted();
    }
}
