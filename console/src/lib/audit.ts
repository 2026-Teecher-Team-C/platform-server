import { ApiError } from './api/client'

const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

// 서버 AuditAction과 같은 순서. 화면에는 한국어 이름과 원래 값을 함께 보여준다.
export const AUDIT_ACTION_LABEL: Record<string, string> = {
  LOGIN: '로그인',
  LOGIN_FAILED: '로그인 실패',
  LOGOUT: '로그아웃',
  CREATE_ADMIN: '관리자 생성',
  UPDATE_ADMIN_ROLE: '관리자 역할 변경',
  DEACTIVATE_ADMIN: '관리자 비활성화',
  ADD_WHITELIST: '화이트리스트 등록',
  UPDATE_WHITELIST: '화이트리스트 사유 수정',
  REMOVE_WHITELIST: '화이트리스트 해제',
  ADD_BLACKLIST: '블랙리스트 등록',
  UPDATE_BLACKLIST: '블랙리스트 사유 수정',
  REMOVE_BLACKLIST: '블랙리스트 해제',
  RESTORE_QUARANTINE: '격리 파일 복원',
  DELETE_QUARANTINE: '격리 파일 삭제',
  ACTIVATE_RULESET: '룰셋 활성화',
  ENABLE_YARA_RULE: 'YARA 룰 켜기',
  DISABLE_YARA_RULE: 'YARA 룰 끄기',
  ADD_BYPASS_DOMAIN: '바이패스 도메인 등록',
  REMOVE_BYPASS_DOMAIN: '바이패스 도메인 삭제',
  UPDATE_FILE_TYPE_POLICY: '파일 형식 정책 변경',
  SET_MANUAL_VERDICT: '수동 판정',
  ISSUE_ENROLLMENT_TOKEN: '등록 토큰 발급',
  REVOKE_AGENT: '에이전트 폐기',
  INVALIDATE_CACHE: '캐시 무효화',
}

// 서버 AuditTargetType과 같다.
export const AUDIT_TARGET_LABEL: Record<string, string> = {
  ADMIN_USER: '관리자',
  AGENT: '에이전트',
  ENROLLMENT_TOKEN: '등록 토큰',
  WHITELIST: '화이트리스트',
  BLACKLIST: '블랙리스트',
  QUARANTINE: '격리 파일',
  RULESET: '룰셋',
  YARA_RULE: 'YARA 룰',
  BYPASS_DOMAIN: '바이패스 도메인',
  FILE_TYPE_POLICY: '파일 형식 정책',
  FILE_VERDICT: '판정',
  CACHE: '캐시',
}

// 서버 검증(UUID, from <= to)을 먼저 확인해 불필요한 400 요청을 줄인다. from/to는 datetime-local 값이다.
export function validateAuditFilters(actorId: string, from: string, to: string) {
  if (actorId.trim() && !UUID_PATTERN.test(actorId.trim())) return '행위자 ID는 UUID 형식이어야 합니다.'
  if (from && to && new Date(from) >= new Date(to)) return '기간의 시작은 끝보다 앞서야 합니다.'
  return ''
}

// detail은 서버가 JSON을 객체로 풀어 준다. JSON이 아니었던 값은 문자열 그대로 온다.
export function formatAuditDetail(detail: unknown) {
  if (detail === null || detail === undefined) return ''
  if (typeof detail === 'string') return detail
  return JSON.stringify(detail, null, 2)
}

export function auditErrorMessage(cause: unknown) {
  if (cause instanceof ApiError) {
    if (cause.status === 403) return '감사 로그는 ADMIN만 조회할 수 있습니다.'
    if (cause.status === 400) {
      const message = (cause.body as { message?: unknown } | null)?.message
      return '조회 조건을 확인해 주세요.' + (typeof message === 'string' ? ' (' + message + ')' : '')
    }
  }
  return '감사 로그를 불러오지 못했습니다.'
}
