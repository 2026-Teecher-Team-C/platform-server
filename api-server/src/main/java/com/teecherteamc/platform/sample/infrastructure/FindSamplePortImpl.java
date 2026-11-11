package com.teecherteamc.platform.sample.infrastructure;

import com.teecherteamc.platform.sample.domain.FindSamplePort;
import com.teecherteamc.platform.sample.domain.SampleDetail;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FindSamplePortImpl implements FindSamplePort {
    private final JdbcTemplate jdbc;
    public FindSamplePortImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<SampleDetail> find(String sha256) {
        // 한 SQL 스냅샷에서 양쪽 현재 행을 읽는다. 한쪽만 남아 있어도 누락된 쪽을 꾸며내지 않는다.
        return jdbc.query("""
                SELECT s.sha256, b.hash_value, b.reason, b.source, b.severity, b.is_active, b.created_at,
                       f.sha256 AS fingerprint_sha, f.tlsh, f.calculation_status, f.algorithm_version,
                       f.provenance, f.provenance_detail, f.calculated_at
                FROM (SELECT hash_value AS sha256 FROM hash_blacklist WHERE hash_type='SHA256' AND hash_value=?
                      UNION SELECT sha256 FROM file_fingerprints WHERE sha256=?) s
                LEFT JOIN hash_blacklist b ON b.hash_type='SHA256' AND b.hash_value=s.sha256
                LEFT JOIN file_fingerprints f ON f.sha256=s.sha256
                """, (rs, row) -> new SampleDetail(rs.getString("sha256"),
                rs.getString("hash_value") == null ? null : new SampleDetail.Blacklist(
                        rs.getString("reason"), rs.getString("source"), rs.getString("severity"),
                        rs.getBoolean("is_active"), rs.getTimestamp("created_at").toInstant()),
                rs.getString("fingerprint_sha") == null ? null : new SampleDetail.Fingerprint(
                        rs.getString("tlsh"), rs.getString("calculation_status"), rs.getString("algorithm_version"),
                        rs.getString("provenance"), rs.getString("provenance_detail"),
                        rs.getTimestamp("calculated_at").toInstant())), sha256, sha256).stream().findFirst();
    }
}
