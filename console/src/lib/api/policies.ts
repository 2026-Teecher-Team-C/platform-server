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
