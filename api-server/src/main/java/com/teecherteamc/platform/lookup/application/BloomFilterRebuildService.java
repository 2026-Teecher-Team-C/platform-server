package com.teecherteamc.platform.lookup.application;

import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import com.teecherteamc.platform.lookup.domain.CachedHashSourcePort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 블룸 필터는 항목을 삭제할 수 없어서, 최신 상태를 유지하는 유일한 방법은 전체 재구축이다.
 * initialDelay=0이라 기동 즉시 1차 빌드가 돌고, 그 뒤 설정된 주기로 반복한다. 재구축 전/중엔
 * 블룸이 비어있거나 오래된 상태일 수 있는데, CheckHashUseCase 쪽에서 이건 안전한 방향으로만
 * 틀린다(아직 블룸에 안 반영된 캐시 히트는 캐시 미스로 떨어져서 다시 엔진까지 가는 것뿐, 잘못된
 * ALLOW는 안 나옴) — 그래서 레이스를 막는 별도 잠금 없이 단순 주기 작업으로 둔다.
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
        bloomFilterPort.rebuild(cachedHashSourcePort.findAllCachedHashes());
    }
}
