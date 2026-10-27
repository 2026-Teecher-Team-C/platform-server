package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRegistered;
import com.teecherteamc.platform.verdict.domain.FileVerdictRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 화이트리스트에 등록된 해시의 기존 판정을 stale로 표시한다.
 * 화이트리스트가 해제되거나 만료된 뒤 예전 차단 판정 캐시로 다시 막히지 않고 재검사되게 하려는 것이다.
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
        // 판정 행이 없으면(한 번도 검사하지 않은 해시) 할 일이 없다
        fileVerdicts.findBySha256(event.sha256()).ifPresent(verdict -> {
            verdict.markStale();
            fileVerdicts.save(verdict);
        });
    }
}
