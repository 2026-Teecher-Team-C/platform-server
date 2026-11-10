package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRegistered;
import com.teecherteamc.platform.verdict.domain.FileVerdict;
import com.teecherteamc.platform.verdict.domain.FileVerdictRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 화이트리스트에 등록된 해시의 판정 캐시를 처리한다.
 * - 기존 판정이 있으면 stale로 표시한다. 화이트리스트가 해제되거나 만료된 뒤 예전 차단 판정 캐시로
 *   다시 막히지 않고 재검사되게 하려는 것이다.
 * - 판정 행이 아예 없으면(엔진이 한 번도 분석한 적 없는 해시를 관리자가 직접 화이트리스트에 등록한
 *   경우) CLEAN/WHITELIST로 새로 만든다. 이게 없으면 download_events.sha256 FK가 안 맞아서
 *   ReportEventUseCase.resolveSafeSha256()이 그 해시를 NULL로 저장해버리고, 그 해시로 통과된
 *   다운로드는 나중에 "누가 받았는지" 찾을 방법이 없어진다(소급 판정 전제조건 깨짐, IMP-9).
 *
 * 커밋 후가 아니라 발행한 쪽 트랜잭션 안에서 바로 처리한다(@EventListener).
 * 그래서 화이트리스트 등록, 감사 로그와 함께 커밋되거나 함께 취소된다.
 */
@Component
class WhitelistEntryRegisteredListener {

    private final FileVerdictRepository fileVerdicts;

    WhitelistEntryRegisteredListener(FileVerdictRepository fileVerdicts) {
        this.fileVerdicts = fileVerdicts;
    }

    @EventListener
    void on(WhitelistEntryRegistered event) {
        Optional<FileVerdict> existing = fileVerdicts.findBySha256(event.sha256());
        if (existing.isPresent()) {
            FileVerdict verdict = existing.get();
            verdict.markStale();
            fileVerdicts.save(verdict);
        } else {
            fileVerdicts.save(FileVerdict.create(
                    event.sha256(), null, null, "CLEAN", "WHITELIST", null, Instant.now()));
        }
    }
}
