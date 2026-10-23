package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntryRepository;
import com.teecherteamc.platform.hashlist.domain.EntrySource;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.Severity;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블랙리스트에 해시를 등록한다. (POST /api/admin/blacklist)
 *
 * 등록 즉시 차단되는 이유: 판정 사슬(CheckHash)이 블랙리스트를 가장 먼저 조회하기 때문이다.
 * 블룸 필터 반영은 고도화(H1)에서 한다.
 */
@Service
public class AddBlacklistEntryUseCase {

    private final BlacklistEntryRepository blacklist;
    private final WhitelistEntryRepository whitelist;
    private final Clock clock;   // 현재 시각을 밖에서 받아야 테스트에서 고정할 수 있다

    public AddBlacklistEntryUseCase(BlacklistEntryRepository blacklist,
                                    WhitelistEntryRepository whitelist,
                                    Clock clock) {
        this.blacklist = blacklist;
        this.whitelist = whitelist;
        this.clock = clock;
    }

    /**
     * 요청 값을 한 덩어리로 묶는다. addedBy는 로그인이 없는 지금은 null이다.
     * (hash_blacklist.added_by가 nullable이라 가능. 인증을 붙이면 컨트롤러에서 채운다)
     */
    public record Command(String sha256, String reason, UUID addedBy) {
    }

    @Transactional   // 검사와 저장이 한 트랜잭션이라 중간에 예외가 나면 모두 취소된다
    public BlacklistEntry add(Command command) {
        // 1. 형식 검증 + 정규화. 틀리면 InvalidHashException (컨트롤러가 400으로 바꾼다)
        HashKey key = HashKey.sha256(command.sha256());
        Instant now = clock.instant();

        // 2. 화이트리스트에 있으면 거절.
        //    isEffectiveAt: 활성이면서 만료 전인 것만 충돌로 본다.
        //    스펙대로 "활성이기만 하면"으로 하려면 e.isEffectiveAt(now)를 e.isActive()로 바꾼다.
        //    (만료됐지만 활성으로 남은 행이 있으면 활성 기준에서는 그 해시를 영영 등록할 수 없다)
        whitelist.findByKey(key)
                .filter(e -> e.isEffectiveAt(now))
                .ifPresent(e -> {
                    throw new HashListConflictException("화이트리스트에 등록된 해시입니다.");
                });

        Optional<BlacklistEntry> existing = blacklist.findByKey(key);

        if (existing.isPresent()) {
            BlacklistEntry entry = existing.get();

            // 3. 이미 활성이면 거절
            if (entry.isActive()) {
                throw new HashListConflictException("이미 블랙리스트에 등록된 해시입니다.");
            }

            // 4. 해제했던 행이 있으면 새로 INSERT하지 않고 되살린다. (PK 충돌 방지)
            entry.reactivate(command.reason(), Severity.HIGH, EntrySource.MANUAL, command.addedBy());
            blacklist.save(entry);
            return entry;
        }

        // 5. 처음 등록. severity는 요청에 없어서 HIGH, source는 MANUAL
        BlacklistEntry entry = BlacklistEntry.register(
                key, command.reason(), Severity.HIGH, EntrySource.MANUAL, command.addedBy(), now);
        blacklist.save(entry);
        return entry;

        // 6. 감사 로그는 다음 단계에서 추가한다.
    }
}