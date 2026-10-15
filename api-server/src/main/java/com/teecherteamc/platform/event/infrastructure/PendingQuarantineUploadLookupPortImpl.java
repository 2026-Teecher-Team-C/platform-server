package com.teecherteamc.platform.event.infrastructure;

import com.teecherteamc.platform.event.domain.PendingQuarantineUpload;
import com.teecherteamc.platform.event.domain.PendingQuarantineUploadLookupPort;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class PendingQuarantineUploadLookupPortImpl implements PendingQuarantineUploadLookupPort {

    private final JdbcTemplate jdbcTemplate;

    PendingQuarantineUploadLookupPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PendingQuarantineUpload> findAndDelete(String sha256) {
        List<PendingQuarantineUpload> rows = jdbcTemplate.query(
                "SELECT file_size, s3_bucket, s3_key FROM pending_quarantine_uploads WHERE sha256 = ?",
                (rs, rowNum) -> new PendingQuarantineUpload(
                        rs.getLong("file_size"), rs.getString("s3_bucket"), rs.getString("s3_key")),
                sha256);
        Optional<PendingQuarantineUpload> result = rows.stream().findFirst();
        if (result.isPresent()) {
            jdbcTemplate.update("DELETE FROM pending_quarantine_uploads WHERE sha256 = ?", sha256);
        }
        return result;
    }
}
