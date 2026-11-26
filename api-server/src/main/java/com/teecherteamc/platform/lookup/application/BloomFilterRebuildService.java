package com.teecherteamc.platform.lookup.application;

import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort.BloomRebuild;
import com.teecherteamc.platform.lookup.domain.BloomRebuildResult;
import com.teecherteamc.platform.lookup.domain.CachedHashSourcePort;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 블룸 필터는 항목을 삭제할 수 없어서, 원천에서 빠진 해시를 치우는 방법은 전체 재구축뿐이다.
 * initialDelay=0이라 기동 즉시 1차 빌드가 돌고, 그 뒤 설정된 주기로 반복한다.
 *
 * <p>file_verdicts 쪽 누락은 안전한 방향으로만 틀린다(캐시 미스로 엔진까지 갈 뿐 잘못된 ALLOW는 없음). 목록(블랙/화이트)
 * 쪽은 블룸 미스가 정확 일치 조회를 생략시키므로 누락되면 안 된다. 보장: 목록 쓰기는 커밋 뒤에 add하고, 재구축은 표시
 * 키(pending)를 세운 <b>뒤에</b> 스냅숏을 읽는다. 표시 전에 커밋된 행은 스냅숏에 있고, 표시 뒤의 add는 pending에도
 * 쓰여 교체 때 합쳐진다.
 */
@Service
public class BloomFilterRebuildService {

    private final CachedHashSourcePort cachedHashSourcePort;
    private final BloomFilterPort bloomFilterPort;

    public BloomFilterRebuildService(CachedHashSourcePort cachedHashSourcePort, BloomFilterPort bloomFilterPort) {
        this.cachedHashSourcePort = cachedHashSourcePort;
        this.bloomFilterPort = bloomFilterPort;
    }

    @Scheduled(fixedRateString = "${lookup.bloom-filter.rebuild-interval-ms}", initialDelay = 0)
    public void rebuild() {
        rebuild(BloomRebuildRequests.STARTUP);
    }

    /** 재구축 한 번. 다른 재구축이 이미 돌고 있으면 시작하지 않고 empty를 준다. reason은 Task 3에서 지표·재요청에 쓴다. */
    public Optional<BloomRebuildResult> rebuild(String reason) {
        Optional<BloomRebuild> started = bloomFilterPort.beginRebuild();
        if (started.isEmpty()) {
            return Optional.empty();
        }
        try (BloomRebuild rebuild = started.get()) {
            // 순서가 정확성의 근거다: 표시(begin) 뒤에 스냅숏을 읽는다.
            return Optional.of(rebuild.complete(cachedHashSourcePort.findAllCachedHashes()));
        }
    }
}
