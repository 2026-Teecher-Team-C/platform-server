package com.teecherteamc.platform.lookup.application;

import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import com.teecherteamc.platform.lookup.domain.CachedHashSourcePort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 블룸 필터는 항목을 삭제할 수 없어서, 최신 상태를 유지하는 유일한 방법은 전체 재구축이다.
 * initialDelay=0이라 기동 즉시 1차 빌드가 돌고, 그 뒤 설정된 주기로 반복한다.
 *
 * <p>file_verdicts 쪽 누락은 안전한 방향으로만 틀린다(캐시 미스로 엔진까지 갈 뿐 잘못된 ALLOW는
 * 없음). 목록(블랙/화이트) 쪽은 블룸 미스가 정확 일치 조회를 생략시키므로 누락되면 안 된다. 보장은 두
 * 가지로 만든다: (1) 목록 쓰기는 커밋 뒤에 add한다 — 재구축의 스냅샷 읽기와 RENAME 사이에 커밋된 행의
 * add는 RENAME에 덮일 수 있다. (2) 그래서 RENAME 뒤에 목록 해시를 다시 읽어 라이브 키에 add한다.
 * 커밋된 목록 행은 이 재조회에 보이거나, 커밋 후 add가 RENAME 뒤에 실행되므로 항상 블룸에 남는다.
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
        // RENAME 이후 재조회: 스냅샷과 RENAME 사이에 커밋된 목록 행을 라이브 블룸에 다시 넣는다.
        cachedHashSourcePort.findAllListHashes().forEach(bloomFilterPort::add);
    }
}
