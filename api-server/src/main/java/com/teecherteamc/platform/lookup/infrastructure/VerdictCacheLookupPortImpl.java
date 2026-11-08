package com.teecherteamc.platform.lookup.infrastructure;

import com.teecherteamc.platform.lookup.domain.CachedVerdict;
import com.teecherteamc.platform.lookup.domain.VerdictCacheLookupPort;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class VerdictCacheLookupPortImpl implements VerdictCacheLookupPort {

    private final JdbcTemplate jdbcTemplate;

    VerdictCacheLookupPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CachedVerdict> find(String sha256) {
        List<CachedVerdict> rows = jdbcTemplate.query(
                """
                SELECT v.verdict, v.verdict_source, v.is_stale OR
                  ((v.verdict='CLEAN' OR v.verdict_source='SIMILARITY') AND NOT EXISTS (
                    SELECT 1 FROM similarity_cache c JOIN similarity_assessments a USING(assessment_id)
                    WHERE c.sha256=v.sha256 AND a.corpus_token=(SELECT token FROM current_similarity_corpus_token))) AS is_stale
                FROM file_verdicts v WHERE v.sha256 = ?
                """,
                (rs, rowNum) -> new CachedVerdict(rs.getString("verdict"), rs.getBoolean("is_stale"), rs.getString("verdict_source")),
                sha256);
        return rows.stream().findFirst();
    }
}
