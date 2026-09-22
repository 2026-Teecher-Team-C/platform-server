/**
 * 격리 컨텍스트. S3 격리 보관과 오탐 복원을 소유한다.
 * 복원은 도메인 이벤트로 알린다 — 화이트리스트 등록, 판정 무효화(is_stale), 캐시 삭제, 감사 로그가 각자 반응한다.
 *
 * <p>테이블: quarantine_files
 * <br>애그리거트 루트: QuarantinedFile
 *
 * <p>하위 패키지: domain / application / infrastructure / interfaces (api-server/README.md 참고)
 */
package com.teecherteamc.platform.quarantine;
