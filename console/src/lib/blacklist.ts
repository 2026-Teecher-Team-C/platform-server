import { ApiError } from './api/client'
import type { BlacklistQuery } from './api/hashlists'

// 새로 등록한 항목은 별도 검색 대신 활성 목록 첫 페이지에서 확인한다.
export function getBlacklistQueryAfterCreate(query: BlacklistQuery): BlacklistQuery {
  return { isActive: true, page: 0, size: query.size ?? 20 }
}

export function getBlacklistQueryAfterStatusChange(query: BlacklistQuery, changedHash: string, nextIsActive: boolean): BlacklistQuery {
  const target = changedHash.toLowerCase()
  // 전환한 항목이 현재 상태 필터에서 빠지면 단건 해시 검색만 비운다. 다른 필터와 페이지 크기는 유지한다.
  return query.hashValue?.toLowerCase() === target && (query.isActive ?? true) !== nextIsActive
    ? { ...query, hashValue: undefined, page: 0 }
    : { ...query }
}

// 서버 HashKey와 @NotBlank의 조건을 폼에서도 확인한다. 심각도와 출처는 서버가 HIGH/MANUAL로 결정한다.
export function validateBlacklistForm(sha256: string, reason: string) {
  if (!/^[0-9a-fA-F]{64}$/.test(sha256.trim())) return 'SHA-256은 64자리 16진수여야 합니다.'
  if (!reason.trim()) return '사유를 입력해 주세요.'
  return ''
}

export function blacklistErrorMessage(cause: unknown) {
  if (cause instanceof ApiError) {
    if (cause.status === 409) return '이미 활성 블랙리스트에 있거나 유효한 화이트리스트에 등록된 해시입니다.'
    if (cause.status === 404) return '활성 항목을 찾을 수 없습니다. 다른 사용자가 변경했을 수 있으니 목록을 새로고침해 주세요.'
    if (cause.status === 400) return '해시와 사유의 입력값을 확인해 주세요.'
    if (cause.status === 401) return '로그인이 만료되었습니다. 다시 로그인해 주세요.'
    if (cause.status === 403) return '블랙리스트를 변경할 권한이 없습니다.'
  }
  // 쓰기 요청의 응답만 끊기면 서버에는 반영됐을 수도 있으므로 무작정 중복 요청하지 않게 안내한다.
  return '요청 결과를 확인하지 못했습니다. 서버에서 처리됐을 수 있으니 목록을 새로고침한 뒤 다시 시도해 주세요.'
}
