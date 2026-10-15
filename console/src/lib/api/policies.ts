import { apiRequest } from './client'
import type { CursorPage } from './types'
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
  cursor?: string
  limit?: number
}

export function getBypassDomains(query: BypassDomainQuery = {}) {
  return apiRequest<CursorPage<BypassDomainItem>>(
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
