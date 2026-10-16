package com.teecherteamc.platform.lookup.domain;

import java.util.List;

/**
 * file_verdicts에 한 번이라도 기록된 모든 sha256을 추적하는 블룸 필터. "없음"이면 그 해시는
 * 확실히 file_verdicts에 행이 없다는 뜻이고(블룸 필터는 거짓 양성만 가능, 거짓 음성은 없음),
 * CheckHashUseCase가 이 신호로 file_verdicts 조회를 생략한다 — CLEAN/MALICIOUS 어느 쪽 캐시
 * 히트든 블룸을 통과해야 하므로, 둘 다 빠짐없이 추적해야 한다.
 */
public interface BloomFilterPort {

    boolean mightContain(String sha256);

    /** 주기적으로 전체를 다시 쓴다 — 블룸 필터는 항목을 삭제할 수 없어서 증분 삭제가 불가능하다. */
    void rebuild(List<String> cachedHashes);
}
