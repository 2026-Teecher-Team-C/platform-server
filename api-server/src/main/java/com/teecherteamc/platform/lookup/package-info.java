/**
 * 해시 조회 사슬. 다운로드마다 호출되는 가장 뜨거운 읽기 경로다.
 *
 * <p>① hash_whitelist → ② hash_blacklist → ③ 블룸 필터 → ④ file_verdicts 순서로 조회하고, 걸리면 그 자리에서 끝난다.
 * 애그리거트를 로딩하지 않고 Redis와 전용 쿼리로 직접 읽는다. 쓰기는 각 컨텍스트의 도메인 모델을 거친다.
 */
package com.teecherteamc.platform.lookup;
