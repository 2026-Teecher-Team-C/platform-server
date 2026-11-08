package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.FileFingerprintStore;
import com.teecherteamc.platform.verdict.domain.FingerprintCalculation;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFileFingerprintStore implements FileFingerprintStore {
    private final JdbcTemplate jdbc;

    public JdbcFileFingerprintStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveVerified(String sha256, FingerprintCalculation result) {
        // 단일 upsert는 신규 파일의 동시 업로드에서도 PK 충돌을 외부로 누출하지 않는다.
        jdbc.update("""
                INSERT INTO file_fingerprints
                    (sha256, tlsh, calculation_status, algorithm_version, provenance)
                VALUES (?, ?, ?, ?, 'VERIFIED_UPLOAD')
                ON CONFLICT (sha256) DO UPDATE SET
                    tlsh = EXCLUDED.tlsh, calculation_status = EXCLUDED.calculation_status,
                    algorithm_version = EXCLUDED.algorithm_version, calculated_at = now(),
                    provenance = EXCLUDED.provenance, provenance_detail = NULL, imported_by = NULL
                WHERE file_fingerprints.calculation_status <> 'CALCULATED'
                    OR EXCLUDED.calculation_status = 'CALCULATED'
                """, sha256, result.tlsh(), result.status(), FingerprintCalculation.ALGORITHM_VERSION);
    }

    @Override
    public boolean lockActiveAdmin(UUID actor) {
        return !jdbc.queryForList("""
                SELECT admin_id FROM admin_users
                WHERE admin_id = ? AND role = 'ADMIN' AND is_active FOR SHARE
                """, actor).isEmpty();
    }

    @Override
    public boolean lockActiveBaseline(String sha256) {
        return !jdbc.queryForList("""
                SELECT hash_value FROM hash_blacklist
                WHERE hash_type = 'SHA256' AND hash_value = ? AND is_active FOR SHARE
                """, sha256).isEmpty();
    }

    @Override
    public boolean saveImported(String sha256, String tlsh, String provenance, UUID actor) {
        return jdbc.update("""
                INSERT INTO file_fingerprints
                    (sha256, tlsh, calculation_status, algorithm_version, provenance, provenance_detail, imported_by)
                VALUES (?, ?, 'CALCULATED', ?, 'ADMIN_IMPORT', ?, ?)
                ON CONFLICT (sha256) DO UPDATE SET
                    tlsh = EXCLUDED.tlsh, calculation_status = EXCLUDED.calculation_status,
                    algorithm_version = EXCLUDED.algorithm_version, calculated_at = now(),
                    provenance = EXCLUDED.provenance, provenance_detail = EXCLUDED.provenance_detail,
                    imported_by = EXCLUDED.imported_by
                WHERE file_fingerprints.provenance = 'ADMIN_IMPORT'
                    OR file_fingerprints.calculation_status <> 'CALCULATED'
                """, sha256, tlsh, FingerprintCalculation.ALGORITHM_VERSION, provenance, actor) > 0;
    }
}
