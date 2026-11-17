package com.teecherteamc.platform;

import static com.teecherteamc.platform.TlshFixtures.REFERENCE;
import static com.teecherteamc.platform.TlshFixtures.digestAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.verdict.domain.FingerprintProvenance;
import com.teecherteamc.platform.verdict.domain.SimilarFingerprintSearchPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 유사 지문 검색의 실제 DB 계약. 같은 DB를 쓰는 다른 테스트가 남긴 지문이 섞이므로 이 테스트가 넣은 행만 본다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SimilarFingerprintSearchIntegrationTest {

    @Autowired SimilarFingerprintSearchPort port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private final List<String> inserted = new ArrayList<>();
    private UUID admin;

    @AfterEach
    void cleanUp() {
        inserted.forEach(sha -> jdbc.update("DELETE FROM file_fingerprints WHERE sha256=?", sha));
        if (admin != null) {
            jdbc.update("DELETE FROM admin_users WHERE admin_id=?", admin);
        }
    }

    private static String newSha() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private String insertCalculated(String tlsh, FingerprintProvenance provenance) {
        String sha = newSha();
        if (provenance == FingerprintProvenance.VERIFIED_UPLOAD) {
            jdbc.update("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) "
                    + "VALUES (?, ?, 'CALCULATED', 'review', 'VERIFIED_UPLOAD')", sha, tlsh);
        } else {
            if (admin == null) {
                admin = UUID.randomUUID();
                jdbc.update("INSERT INTO admin_users(admin_id, username, password_hash) VALUES (?, ?, 'unused')",
                        admin, "similar-search-" + admin);
            }
            jdbc.update("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance, "
                    + "provenance_detail, imported_by) VALUES (?, ?, 'CALCULATED', 'review', 'ADMIN_IMPORT', 'fixture', ?)",
                    sha, tlsh, admin);
        }
        inserted.add(sha);
        return sha;
    }

    private String insertUncalculated(String status) {
        String sha = newSha();
        jdbc.update("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) "
                + "VALUES (?, NULL, ?, 'review', 'VERIFIED_UPLOAD')", sha, status);
        inserted.add(sha);
        return sha;
    }

    /** 이 테스트가 넣은 행만 골라 (sha -> 거리)와 스캔한 행 수를 돌려준다. */
    private record Scan(Map<String, Integer> found, long scanned) {}

    private Scan scan(String reference, Set<FingerprintProvenance> provenances, int maxDistance) {
        Map<String, Integer> found = new HashMap<>();
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        Long scanned = tx.execute(status -> port.scan(reference, provenances, maxDistance, (sha, distance) -> {
            if (inserted.contains(sha)) {
                found.put(sha, distance);
            }
        }));
        return new Scan(found, scanned);
    }

    private static final Set<FingerprintProvenance> VERIFIED = Set.of(FingerprintProvenance.VERIFIED_UPLOAD);

    @Test
    void 임계값_30은_30까지_70은_70까지_포함하고_거리를_그대로_알려준다() {
        String at30 = insertCalculated(digestAt(30), FingerprintProvenance.VERIFIED_UPLOAD);
        String at31 = insertCalculated(digestAt(31), FingerprintProvenance.VERIFIED_UPLOAD);
        String at70 = insertCalculated(digestAt(70), FingerprintProvenance.VERIFIED_UPLOAD);
        String at71 = insertCalculated(digestAt(71), FingerprintProvenance.VERIFIED_UPLOAD);

        assertThat(scan(REFERENCE, VERIFIED, 30).found()).containsOnly(Map.entry(at30, 30));
        assertThat(scan(REFERENCE, VERIFIED, 70).found())
                .containsOnly(Map.entry(at30, 30), Map.entry(at31, 31), Map.entry(at70, 70))
                .doesNotContainKey(at71);
    }

    @Test
    void 계산되지_않은_지문은_건너뛰고_스캔은_정상으로_끝난다() {
        String tooShort = insertUncalculated("TOO_SHORT");
        String lowEntropy = insertUncalculated("LOW_ENTROPY");
        String near = insertCalculated(digestAt(10), FingerprintProvenance.VERIFIED_UPLOAD);

        assertThat(scan(REFERENCE, VERIFIED, 70).found()).containsOnlyKeys(near)
                .doesNotContainKeys(tooShort, lowEntropy);
    }

    @Test
    void 출처_인자로_스캔_대상을_고른다() {
        String verified = insertCalculated(digestAt(10), FingerprintProvenance.VERIFIED_UPLOAD);
        String imported = insertCalculated(digestAt(10), FingerprintProvenance.ADMIN_IMPORT);

        assertThat(scan(REFERENCE, Set.of(FingerprintProvenance.VERIFIED_UPLOAD), 70).found()).containsOnlyKeys(verified);
        assertThat(scan(REFERENCE, Set.of(FingerprintProvenance.ADMIN_IMPORT), 70).found()).containsOnlyKeys(imported);
        assertThat(scan(REFERENCE, Set.of(FingerprintProvenance.values()), 70).found())
                .containsOnlyKeys(verified, imported);
    }

    @Test
    void 전송_묶음_256건_뒤에_있는_가까운_지문도_끝까지_읽어서_찾는다() {
        String far = digestAt(71);
        for (int i = 0; i < 300; i++) {
            insertCalculated(far, FingerprintProvenance.VERIFIED_UPLOAD);
        }
        String near = insertCalculated(digestAt(10), FingerprintProvenance.VERIFIED_UPLOAD);

        Scan result = scan(REFERENCE, VERIFIED, 70);

        assertThat(result.found()).containsOnlyKeys(near);
        assertThat(result.scanned()).isGreaterThanOrEqualTo(301);
    }

    @Test
    void 해석할_수_없는_기준_TLSH와_빈_출처는_거부한다() {
        assertThatThrownBy(() -> scan("not-a-tlsh", VERIFIED, 70)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scan(REFERENCE, Set.of(), 70)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 계산된_지문만_기준으로_조회된다() {
        String calculated = insertCalculated(digestAt(10), FingerprintProvenance.VERIFIED_UPLOAD);
        String tooShort = insertUncalculated("TOO_SHORT");
        var tx = new TransactionTemplate(transactions);

        tx.executeWithoutResult(status -> {
            assertThat(port.calculatedTlsh(calculated)).contains(digestAt(10));
            assertThat(port.calculatedTlsh(tooShort)).isEmpty();
            assertThat(port.calculatedTlsh(newSha())).isEmpty();
        });
    }

    @Test
    void 트랜잭션_밖에서는_호출할_수_없다() {
        assertThatThrownBy(() -> port.scan(REFERENCE, VERIFIED, 70, (sha, distance) -> {}))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
