package com.teecherteamc.platform.lookup.domain;

import java.util.Collection;
import java.util.List;

/**
 * 정확 일치 조회 대상(file_verdicts ∪ 블랙리스트 ∪ 화이트리스트)의 sha256을 모두 담는 블룸 필터.
 * "없음"이면 그 해시는 확실히 세 곳 어디에도 없다는 뜻이고(블룸 필터는 거짓 양성만 가능, 거짓 음성은 없음),
 * CheckHashUseCase가 이 신호로 file_verdicts 조회를 생략한다 — CLEAN/MALICIOUS 어느 쪽 캐시
 * 히트든 블룸을 통과해야 하므로, 둘 다 빠짐없이 추적해야 한다.
 */
public interface BloomFilterPort {

    boolean mightContain(String sha256);

    /**
     * 해시 한 건을 즉시 추가한다. 목록 등록·file_verdicts 신규 저장 직후 호출해서 재구축을 기다리지 않게
     * 한다. Redis 장애는 로그만 남기고 삼킨다 — 누락은 다음 재구축이 메우고, 호출한 쓰기를 실패시키지 않는다.
     */
    void add(String sha256);

    /** 여러 건을 Redis 왕복 한 번(파이프라인)으로 추가한다. 일괄 가져오기용. 실패 처리는 add와 같다. */
    void addAll(Collection<String> sha256s);

    /** 주기적으로 전체를 다시 쓴다 — 블룸 필터는 항목을 삭제할 수 없어서 증분 삭제가 불가능하다. */
    void rebuild(List<String> cachedHashes);
}
