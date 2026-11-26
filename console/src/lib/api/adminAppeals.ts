import { apiRequest } from './client'
import type { PageResponse } from './types'
import { toQueryString } from './types'
import type { AppealStatus } from './appeals'

// 서버 appeal/application/AdminAppealListItem·AdminAppealDetail과 1:1. 값이 없으면 null이다.
// evidenceKind·threat는 제출 시 저장한 판정 근거 스냅숏이며, 그 이전 소명은 null이다.
export type AppealEvidenceKind = 'BLACKLIST' | 'SIMILARITY' | 'YARA' | 'CACHE' | 'NONE'

export type AdminAppealListItem = {
  appealId: string
  status: AppealStatus
  createdAt: string
  decidedAt: string | null
  hostname: string | null
  filename: string | null
  sha256: string
  evidenceKind: AppealEvidenceKind | null
  threat: string | null
  // 사유의 앞 80자. 직원이 쓴 값이라 텍스트로만 보여 준다.
  reasonPreview: string
}

export type AdminAppealSimilarity = {
  classification: string
  distance: number | null
  matchedSampleSha256: string | null
}

export type AdminAppealDetail = AdminAppealListItem & {
  eventId: string
  agentId: string
  reason: string
  decisionNote: string | null
  // 결정한 관리자 계정명
  decidedBy: string | null
  // 상세 조회 시점에 이 해시가 활성 블랙리스트에 있는지. 승인하면 해제된다.
  blacklisted: boolean
  blockedAt: string | null
  similarity: AdminAppealSimilarity | null
}

export type AdminAppealQuery = {
  status?: AppealStatus
  // page는 0부터. size 상한은 서버 기준 200.
  page?: number
  size?: number
}

function appealPath(appealId: string) {
  return '/api/admin/appeals/' + encodeURIComponent(appealId)
}

export function listAppeals(query: AdminAppealQuery = {}, signal?: AbortSignal) {
  return apiRequest<PageResponse<AdminAppealListItem>>('/api/admin/appeals' + toQueryString(query), { signal })
}

export function getAdminAppeal(appealId: string) {
  return apiRequest<AdminAppealDetail>(appealPath(appealId))
}

// 승인 의견은 선택이다. 비우면 본문을 생략해 서버가 화이트리스트 사유를 "소명 승인"으로 남긴다.
export function approveAppeal(appealId: string, note?: string) {
  const trimmed = note?.trim()
  return apiRequest<AdminAppealDetail>(appealPath(appealId) + '/approve', {
    method: 'POST', ...(trimmed ? { body: JSON.stringify({ note: trimmed }) } : {}),
  })
}

// 거절 의견은 필수다. 직원 소명 페이지에 "담당자 의견"으로 그대로 보인다.
export function rejectAppeal(appealId: string, note: string) {
  return apiRequest<AdminAppealDetail>(appealPath(appealId) + '/reject', {
    method: 'POST', body: JSON.stringify({ note: note.trim() }),
  })
}
