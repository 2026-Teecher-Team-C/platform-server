/**
 * 정책 컨텍스트. 바이패스 도메인과 파일 타입별 검사 정책을 소유한다.
 * 검사 서버 장애 시 동작은 정책이 아니다 — fail-close 고정이며 토글을 두지 않는다.
 *
 * <p>테이블: bypass_domains, file_type_policies
 * <br>애그리거트 루트: BypassDomain, FileTypePolicy
 *
 * <p>하위 패키지: domain / application / infrastructure / interfaces (api-server/README.md 참고)
 */
package com.teecherteamc.platform.policy;
