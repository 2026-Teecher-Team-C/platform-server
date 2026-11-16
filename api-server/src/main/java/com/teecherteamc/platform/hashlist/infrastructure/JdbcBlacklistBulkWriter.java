package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.BlacklistBulkWriter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcBlacklistBulkWriter implements BlacklistBulkWriter {

    private final JdbcTemplate jdbc;

    JdbcBlacklistBulkWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForImport(List<String> sortedSha256s) {
        // 한 건씩 순서대로 잡는다. 정렬 순서가 묶음끼리 같아야 서로 교착하지 않는다.
        for (String sha256 : sortedSha256s) {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(similarity_lock_key('eligibility'), hashtext(?))",
                    sha256);
        }
        jdbc.execute("SELECT pg_advisory_xact_lock(similarity_lock_key('corpus')::bigint)");
    }

    @Override
    public Map<String, Boolean> findExisting(Collection<String> sha256s) {
        Map<String, Boolean> result = new HashMap<>();
        jdbc.query("""
                SELECT hash_value, is_active FROM hash_blacklist
                WHERE hash_type = 'SHA256' AND hash_value = ANY(?)
                """, rs -> {
            result.put(rs.getString(1), rs.getBoolean(2));
        }, (Object) sha256s.toArray(String[]::new));
        return result;
    }

    @Override
    public Set<String> findEffectiveWhitelisted(Collection<String> sha256s, Instant now) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT hash_value FROM hash_whitelist
                WHERE hash_type = 'SHA256' AND hash_value = ANY(?) AND is_active
                  AND (expires_at IS NULL OR expires_at > ?)
                """, String.class, sha256s.toArray(String[]::new), Timestamp.from(now)));
    }

    @Override
    public List<String> insertImported(Map<String, String> reasonBySha, UUID addedBy, Instant now) {
        List<String> shas = new ArrayList<>(reasonBySha.keySet());
        List<Object[]> args = shas.stream()
                .map(sha -> new Object[] {sha, reasonBySha.get(sha), addedBy, Timestamp.from(now)})
                .toList();
        int[] counts = jdbc.batchUpdate("""
                INSERT INTO hash_blacklist (hash_type, hash_value, reason, severity, source, added_by, created_at)
                VALUES ('SHA256', ?, ?, 'HIGH', 'IMPORT', ?, ?)
                ON CONFLICT (hash_type, hash_value) DO NOTHING
                """, args);
        List<String> inserted = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                inserted.add(shas.get(i));
            }
        }
        return inserted;
    }
}
