package com.teecherteamc.platform.lookup.domain;

import java.util.List;

/**
 * 블룸 필터 재구축의 원천. file_verdicts에 한 번이라도 기록된 모든 sha256(검증 결과·is_stale
 * 여부 무관)을 읽는다 — 블룸이 "한 번도 캐시된 적 없는 해시"를 가려내는 용도라, CLEAN/MALICIOUS를
 * 가리지 않고 전부 포함해야 한다(가려내면 CLEAN 캐시 히트까지 같이 무력화된다).
 */
public interface CachedHashSourcePort {

    List<String> findAllCachedHashes();
}
