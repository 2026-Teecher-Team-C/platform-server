/**
 * 감사 컨텍스트. 관리자 행위를 기록한다. action / target_type 값은 설계 레포의 감사 로그 규격을 따른다
 * (AuditAction, AuditTargetType).
 *
 * <p>테이블: audit_logs
 * <br>애그리거트 루트: AuditLog
 *
 * <p>다른 컨텍스트의 관리자 쓰기 API는 audit_logs에 직접 쓰지 않고 application의 AuditLogRecorder를
 * 자기 트랜잭션 안에서 부른다. 본 작업과 감사 로그는 함께 커밋되거나 함께 롤백된다.
 * append 전용이라 UPDATE·DELETE를 하지 않는다. 조회 API(GET /api/admin/audit-logs)는 아직 없다.
 *
 * <p>하위 패키지: domain / application / infrastructure / interfaces (api-server/README.md 참고)
 */
package com.teecherteamc.platform.audit;
