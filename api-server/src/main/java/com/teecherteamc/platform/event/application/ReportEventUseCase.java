package com.teecherteamc.platform.event.application;

import com.teecherteamc.platform.event.domain.DownloadEvent;
import com.teecherteamc.platform.event.domain.DownloadEventRepository;
import com.teecherteamc.platform.event.domain.FileVerdictExistsPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportEventUseCase {

    private final DownloadEventRepository downloadEventRepository;
    private final FileVerdictExistsPort fileVerdictExistsPort;

    public ReportEventUseCase(
            DownloadEventRepository downloadEventRepository, FileVerdictExistsPort fileVerdictExistsPort) {
        this.downloadEventRepository = downloadEventRepository;
        this.fileVerdictExistsPort = fileVerdictExistsPort;
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
