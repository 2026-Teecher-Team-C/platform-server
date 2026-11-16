package com.teecherteamc.platform.lookup.domain;

import java.util.List;

/**
 * 블룸 필터 재구축의 원천: file_verdicts ∪ 활성 블랙리스트 ∪ 유효 화이트리스트의 sha256.
 * file_verdicts는 검증 결과·is_stale 여부 무관 전부 포함한다(가려내면 CLEAN 캐시 히트까지 무력화된다).
 * 블룸 미스가 목록 조회까지 생략시키므로 목록 해시가 하나라도 빠지면 안 된다. 거짓 양성은 안전하므로
 * 비활성/만료 행이 섞여도 괜찮다.
 */
public interface CachedHashSourcePort {

    List<String> findAllCachedHashes();
}
