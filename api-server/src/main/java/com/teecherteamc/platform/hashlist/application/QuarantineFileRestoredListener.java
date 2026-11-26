package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.quarantine.domain.QuarantineFileRestored;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 격리 파일 복원을 받아 화이트리스트를 등록(또는 재활성화)하고, 같은 해시에 활성 블랙리스트가
 * 있으면 해제한다. quarantine이 hashlist 애그리거트를 직접 건드리지 않도록 여기서 대신 처리한다.
 * 실제 처리는 소명 승인과 함께 쓰는 {@link WhitelistGrant}가 한다.
 *
 * <p>커밋 후가 아니라 발행한 쪽(복원) 트랜잭션 안에서 바로 처리한다(@EventListener) — 격리 상태
 * 변경, 화이트리스트 등록, 블랙리스트 해제가 함께 커밋되거나 함께 취소된다.
 */
@Component
class QuarantineFileRestoredListener {

    private final WhitelistGrant whitelistGrant;
    private final Clock clock;

    QuarantineFileRestoredListener(WhitelistGrant whitelistGrant, Clock clock) {
        this.whitelistGrant = whitelistGrant;
        this.clock = clock;
    }

    @EventListener
    void on(QuarantineFileRestored event) {
        whitelistGrant.grant(HashKey.sha256(event.sha256()), event.reason(), event.adminId(), event.ipAddress(),
                WhitelistGrant.Origin.quarantine(event.quarantineId()), clock.instant());
    }
}
