/**
 * 판정 컨텍스트. 해시 단위 판정 캐시와 탐지 엔진 실행 기록을 소유한다.
 *
 * <p>테이블: file_verdicts, analyses, analysis_matches
 * <br>애그리거트 루트: FileVerdict (sha256)
 *
 * <p>하위 패키지: domain / application / infrastructure / interfaces (api-server/README.md 참고)
 */
package com.teecherteamc.platform.verdict;
