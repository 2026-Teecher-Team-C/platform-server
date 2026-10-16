package com.teecherteamc.platform.event.application;

import com.teecherteamc.platform.event.domain.DownloadEvent;
import com.teecherteamc.platform.event.domain.DownloadEventRepository;
import com.teecherteamc.platform.event.domain.FileVerdictExistsPort;
import com.teecherteamc.platform.event.domain.PendingQuarantineUpload;
import com.teecherteamc.platform.event.domain.PendingQuarantineUploadLookupPort;
import com.teecherteamc.platform.event.domain.QuarantineRecorderPort;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReportEventUseCase.class);
    private static final String DECISION_BLOCKED = "BLOCKED";

    private final DownloadEventRepository downloadEventRepository;
    private final FileVerdictExistsPort fileVerdictExistsPort;
    private final PendingQuarantineUploadLookupPort pendingQuarantineUploadLookupPort;
    private final QuarantineRecorderPort quarantineRecorderPort;

    public ReportEventUseCase(
            DownloadEventRepository downloadEventRepository,
            FileVerdictExistsPort fileVerdictExistsPort,
            PendingQuarantineUploadLookupPort pendingQuarantineUploadLookupPort,
            QuarantineRecorderPort quarantineRecorderPort) {
        this.downloadEventRepository = downloadEventRepository;
        this.fileVerdictExistsPort = fileVerdictExistsPort;
        this.pendingQuarantineUploadLookupPort = pendingQuarantineUploadLookupPort;
        this.quarantineRecorderPort = quarantineRecorderPort;
    }

    @Transactional
    public void reportEvent(ReportEventCommand command) {
        DownloadEvent event = DownloadEvent.record(
                command.eventId(),
                command.agentId(),
                resolveSafeSha256(command.sha256()),
                command.requestHost(),
                command.url(),
                command.filename(),
                command.mimeType(),
                command.contentDisposition(),
                command.fileSize(),
                command.decision(),
                command.decisionSource(),
                command.cacheHit(),
                command.bytesUploaded(),
                command.heldAt(),
                command.decidedAt(),
                command.holdDurationMs());
        downloadEventRepository.save(event);

        if (DECISION_BLOCKED.equals(command.decision())) {
            recordQuarantineIfPending(event, command);
        }
    }

    /**
     * SubmitFile이 S3에 올려둔 뒤 pending_quarantine_uploads에 남겨둔 업로드가 있으면, 여기서
     * 비로소 quarantine_files 행을 만든다(download_events가 방금 생겨서 event_id FK가 만족됨).
     * resolveSafeSha256()이 NULL로 바꾼 값이 아니라 커맨드의 원본 sha256으로 조회해야 한다 — 격리
     * 대상인데 file_verdicts에 없는 상태는 비정상이라 조용히 넘기지 않고 로그를 남긴다.
     *
     * <p>알려진 범위 제한: 같은 파일이 이미 한 번 격리된 뒤 다시 다운로드되면(캐시 히트 BLOCK),
     * SubmitFile이 엔진을 다시 안 부르니 pending 행도 새로 안 생긴다 — 그 다운로드는 BLOCKED로
     * 처리되지만 quarantine_files 행은 안 생긴다(최초 1건만 기록). 격리 트리거를 "신규 엔진 호출"로
     * 한정하기로 한 결정의 직접적인 결과.
     */
    private void recordQuarantineIfPending(DownloadEvent event, ReportEventCommand command) {
        String rawSha256 = command.sha256();
        if (rawSha256 == null || rawSha256.isBlank()) {
            return;
        }
        Optional<PendingQuarantineUpload> pending = pendingQuarantineUploadLookupPort.findAndDelete(rawSha256);
        if (pending.isEmpty()) {
            return; // BLOCKED가 전부 격리 대상인 건 아니다(블랙리스트/바이패스 등).
        }
        if (!fileVerdictExistsPort.exists(rawSha256)) {
            // quarantine_files.sha256 -> file_verdicts FK가 있어서 이 상태로는 insert가 실패한다 —
            // SubmitFile이 먼저 file_verdicts를 저장한 뒤에만 pending 행을 만들므로 정상 경로에선
            // 일어날 수 없다. 조용히 넘기지 않고 남겨서 원인을 추적할 수 있게 한다.
            log.error("격리 대상인데 file_verdicts에 sha256={}가 없다 — quarantine_files 생성 건너뜀", rawSha256);
            return;
        }
        PendingQuarantineUpload upload = pending.get();
        quarantineRecorderPort.record(
                event.getEventId(), rawSha256, event.getAgentId(), command.filename(), upload.fileSize(),
                upload.bucket(), upload.key(), Instant.now());
    }

    private String resolveSafeSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return null;
        }
        // download_events.sha256 -> file_verdicts(sha256) FK. 화이트리스트 히트/바이패스 다운로드는
        // file_verdicts에 행이 없을 수 있다(CheckHash가 아직 없어서 더더욱). FK 위반 대신 null로 저장한다.
        // CheckHash가 생긴 뒤에도 이 분기가 여전히 필요한지 팀과 재확인할 것 — 지우지 말 것.
        return fileVerdictExistsPort.exists(sha256) ? sha256 : null;
    }
}
