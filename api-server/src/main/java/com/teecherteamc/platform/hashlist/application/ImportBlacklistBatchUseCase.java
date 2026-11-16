package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.hashlist.domain.BlacklistBulkWriter;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import com.teecherteamc.platform.verdict.application.ImportFileFingerprints;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 위협 데이터(MalwareBazaar 등)의 해시와 TLSH를 묶음으로 넣는다. (POST /api/admin/blacklist/import)
 *
 * 묶음 하나가 한 트랜잭션이다. 117만 건을 한 번에 넣으면 행 트리거(V10)가 잡는 advisory 잠금이 Postgres 잠금 표를
 * 넘기 때문에, 호출자(스크립트)가 1,000건 이하로 나눠 보낸다. 관리자가 해제한 항목은 되살리지 않고, 화이트리스트는
 * 건드리지 않는다. 지문은 이 묶음 처리 뒤 활성인 SHA에만 넣는다.
 */
@Service
public class ImportBlacklistBatchUseCase {

    public static final int MAX_ENTRIES = 1000;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_LABEL = 200;

    private final BlacklistBulkWriter writer;
    private final ImportFileFingerprints fingerprints;
    private final AuditLogRecorder auditLogRecorder;
    private final BloomFilterPort bloomFilter;
    private final Clock clock;

    public ImportBlacklistBatchUseCase(BlacklistBulkWriter writer, ImportFileFingerprints fingerprints,
            AuditLogRecorder auditLogRecorder, BloomFilterPort bloomFilter, Clock clock) {
        this.writer = writer;
        this.fingerprints = fingerprints;
        this.auditLogRecorder = auditLogRecorder;
        this.bloomFilter = bloomFilter;
        this.clock = clock;
    }

    public record Entry(String sha256, String tlsh, String label) {
    }

    public record Command(String source, List<Entry> entries, UUID actor, String ip) {
    }

    public record Result(int received, int added, int skippedActive, int skippedInactive, int skippedWhitelisted,
            int invalid, int fingerprintsAdded, int fingerprintsSkipped) {
    }

    @Transactional
    public Result importBatch(Command command) {
        Instant now = clock.instant();
        int invalid = 0;
        // 같은 SHA가 묶음 안에 두 번 오면 처음 것만 쓴다.
        Map<String, Entry> bySha = new LinkedHashMap<>();
        for (Entry entry : command.entries()) {
            String sha = entry.sha256() == null ? "" : entry.sha256().trim().toLowerCase(Locale.ROOT);
            if (!SHA256.matcher(sha).matches() || bySha.containsKey(sha)) {
                invalid++;
                continue;
            }
            bySha.put(sha, entry);
        }
        List<String> sorted = bySha.keySet().stream().sorted().toList();
        writer.lockForImport(sorted);
        Map<String, Boolean> existing = writer.findExisting(sorted);
        Set<String> whitelisted = writer.findEffectiveWhitelisted(sorted, now);

        int skippedActive = 0;
        int skippedInactive = 0;
        int skippedWhitelisted = 0;
        Set<String> active = new HashSet<>();
        Map<String, String> reasonBySha = new LinkedHashMap<>();
        for (String sha : sorted) {
            Boolean isActive = existing.get(sha);
            if (Boolean.TRUE.equals(isActive)) {
                skippedActive++;
                active.add(sha);
            } else if (Boolean.FALSE.equals(isActive)) {
                skippedInactive++;
            } else if (whitelisted.contains(sha)) {
                skippedWhitelisted++;
            } else {
                reasonBySha.put(sha, reason(command.source(), bySha.get(sha).label()));
            }
        }
        List<String> inserted = writer.insertImported(reasonBySha, command.actor(), now);
        active.addAll(inserted);

        Map<String, String> tlshBySha = new LinkedHashMap<>();
        for (String sha : sorted) {
            String tlsh = bySha.get(sha).tlsh();
            if (active.contains(sha) && tlsh != null && !tlsh.isBlank()) {
                tlshBySha.put(sha, tlsh.trim());
            }
        }
        ImportFileFingerprints.BatchResult fp = tlshBySha.isEmpty()
                ? new ImportFileFingerprints.BatchResult(0, 0)
                : fingerprints.importBatch(tlshBySha, command.source(), command.actor());

        Result result = new Result(command.entries().size(), inserted.size(), skippedActive, skippedInactive,
                skippedWhitelisted, invalid, fp.added(), fp.rejected());
        recordAudit(command, result);
        BloomAfterCommit.addAll(bloomFilter, inserted);
        return result;
    }

    private static String reason(String source, String label) {
        if (label == null || label.isBlank() || label.equalsIgnoreCase("n/a")) {
            return source;
        }
        String trimmed = label.trim();
        return source + ": " + (trimmed.length() > MAX_LABEL ? trimmed.substring(0, MAX_LABEL) : trimmed);
    }

    private void recordAudit(Command command, Result result) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", command.source());
        detail.put("received", result.received());
        detail.put("added", result.added());
        detail.put("skippedActive", result.skippedActive());
        detail.put("skippedInactive", result.skippedInactive());
        detail.put("skippedWhitelisted", result.skippedWhitelisted());
        detail.put("invalid", result.invalid());
        detail.put("fingerprintsAdded", result.fingerprintsAdded());
        auditLogRecorder.record(command.actor(), AuditAction.IMPORT_BLACKLIST, AuditTargetType.BLACKLIST,
                "BATCH", detail, command.ip());
    }
}
