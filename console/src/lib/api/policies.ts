import { apiRequest } from './client'
import type { PageResponse } from './types'
import { toQueryString } from './types'

export type BypassDomainItem = {
  bypassId: string
  host: string
  category: string
  reason: string
  isActive: boolean
  createdAt: string
  updatedAt?: string
}

export type BypassDomainQuery = {
  category?: string
  isActive?: boolean
  host?: string
  page?: number
  size?: number
}

export function getBypassDomains(query: BypassDomainQuery = {}) {
  // 화면의 '검색어'는 host 쿼리로 전달한다. 서버 검색 범위는 도메인 패턴의 부분 일치다.
  return apiRequest<PageResponse<BypassDomainItem>>(
    '/api/admin/bypass-domains' + toQueryString(query),
  )
}

export type CreateBypassDomainInput = { host: string; category: string; reason: string }

// 실제 POST는 신규 등록과 비활성 행 재등록 모두 201과 같은 항목을 반환한다.
export function createBypassDomain(input: CreateBypassDomainInput) {
  return apiRequest<BypassDomainItem>('/api/admin/bypass-domains', {
    method: 'POST', body: JSON.stringify(input),
  })
}

// DELETE는 영구 삭제가 아닌 비활성화다. 204라서 응답 항목을 추측하지 않고 목록을 다시 조회한다.
export async function deactivateBypassDomain(bypassId: string): Promise<void> {
  await apiRequest<unknown>('/api/admin/bypass-domains/' + encodeURIComponent(bypassId), { method: 'DELETE' })
}

export type FileTypePolicyItem = {
  fileTypePolicyId: string
  fileType: string
  inspectionLevel: string
  maxInspectSizeBytes: number | null
  oversizeAction: string
  isActive: boolean
  updatedBy: {
    adminId: string
    username: string
  } | null
  updatedAt: string
  createdAt: string
}

export type FileTypePolicyQuery = {
  fileType?: string
  isActive?: boolean
}

export function getFileTypePolicies(query: FileTypePolicyQuery = {}) {
  return apiRequest<{ items: FileTypePolicyItem[] }>(
    '/api/admin/file-type-policies' + toQueryString(query),
  )
}
// ── 유사도(TLSH) 임계값 (IMP-8) ──────────────────────────
// 서버 API: GET·PUT /api/admin/similarity-policy (서버 PR #182).
// 조회는 ADMIN·ANALYST, 변경은 ADMIN만 가능하다.
//
// 거리(distance)는 TLSH 값 차이로, 작을수록 비슷하다.
//   거리 <= blockDistance            -> 차단(BLOCK)
//   blockDistance < 거리 <= warnDistance -> 경고(WARN, 알림만)
//   그 위                           -> 무시
export type SimilarityPolicyLimits = {
  blockMin: number // 차단 임계값 최솟값
  blockMax: number // 차단 임계값 최댓값
  warnMax: number // 경고 임계값 최댓값. 최솟값은 서버가 주지 않고 "차단 + 1"이다.
}

export type SimilarityPolicy = {
  blockDistance: number
  warnDistance: number
  // 허용 범위. 화면 검증과 안내 문구는 이 값을 쓰고, 숫자를 화면에 따로 박아 두지 않는다.
  limits: SimilarityPolicyLimits
}

export type UpdateSimilarityPolicyInput = {
  blockDistance: number
  warnDistance: number
}

export function getSimilarityPolicy() {
  return apiRequest<SimilarityPolicy>('/api/admin/similarity-policy')
}

// PUT은 저장한 뒤의 현재 값을 GET과 같은 모양으로 돌려준다(limits 포함).
// 값이 그대로여도 200이고, 범위를 벗어나거나 필드가 빠지면 400이다.
export function updateSimilarityPolicy(input: UpdateSimilarityPolicyInput) {
  return apiRequest<SimilarityPolicy>('/api/admin/similarity-policy', {
    method: 'PUT', body: JSON.stringify(input),
  })
}