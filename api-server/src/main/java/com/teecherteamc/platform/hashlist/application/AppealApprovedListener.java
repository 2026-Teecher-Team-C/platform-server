package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.appeal.domain.AppealApproved;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 소명 승인을 받아 그 해시를 화이트리스트에 등록(또는 재활성화, 출처 origin_appeal_id)하고 같은 해시의 활성
 * 블랙리스트를 해제한다. 격리 복원과 같은 {@link WhitelistGrant}를 쓴다.
 *
 * <p>승인 트랜잭션 안에서 동기로 처리한다(@EventListener) — 소명 상태와 화이트리스트가 함께 커밋되거나 함께 취소된다.
 */
@Component
class AppealApprovedListener {

    private final WhitelistGrant whitelistGrant;
    private final Clock clock;

    AppealApprovedListener(WhitelistGrant whitelistGrant, Clock clock) {
        this.whitelistGrant = whitelistGrant;
        this.clock = clock;
    }

    @EventListener
    void on(AppealApproved event) {
        whitelistGrant.grant(HashKey.sha256(event.sha256()), event.reason(), event.adminId(), event.ipAddress(),
                WhitelistGrant.Origin.appeal(event.appealId()), clock.instant());
    }
}
