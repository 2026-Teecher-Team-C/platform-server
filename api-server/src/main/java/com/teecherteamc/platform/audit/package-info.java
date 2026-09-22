/**
 * 감사 컨텍스트. 관리자 행위를 기록한다. action / target_type 값은 설계 레포의 감사 로그 규격을 따른다.
 *
 * <p>테이블: audit_logs
 * <br>애그리거트 루트: AuditLog
 *
 * <p>하위 패키지: domain / application / infrastructure / interfaces (api-server/README.md 참고)
 */
package com.teecherteamc.platform.audit;
