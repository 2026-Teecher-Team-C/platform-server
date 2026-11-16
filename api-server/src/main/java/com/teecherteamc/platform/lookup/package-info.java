/**
 * 해시 조회 사슬. 다운로드마다 호출되는 가장 뜨거운 읽기 경로다.
 *
 * <p>① 블룸 필터 → (hit) ② hash_whitelist → ③ hash_blacklist → ④ file_verdicts → (miss·미결) ⑤ 에이전트가 보낸
 * TLSH 비교 순서로 조회하고, 걸리면 그 자리에서 끝난다. 블룸은 목록·판정 캐시의 모든 해시를 담으므로 miss면
 * ②~④를 건너뛴다. ⑤의 결과는 그 이벤트의 근거로만 남고 file_verdicts·similarity_cache에는 쓰지 않는다.
 * 애그리거트를 로딩하지 않고 Redis와 전용 쿼리로 직접 읽는다. 쓰기는 각 컨텍스트의 도메인 모델을 거친다.
 */
package com.teecherteamc.platform.lookup;
