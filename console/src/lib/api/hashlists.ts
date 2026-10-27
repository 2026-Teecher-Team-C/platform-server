import { apiRequest } from './client'
import type { CursorPage, PageResponse } from './types'
import { toQueryString } from './types'

export type AdminSummary = {
  adminId: string
  username: string
}

export type WhitelistOrigin = 'RESTORED' | 'PREREGISTERED'

export type WhitelistItem = {
  hashType: 'SHA256'
  hashValue: string
  reason: string
  origin: WhitelistOrigin
  originQuarantineId: string | null
  addedBy?: AdminSummary
  isActive: boolean
  expiresAt: string | null
  createdAt: string
  updatedAt?: string
}

// 서버 WhitelistController.list와 1:1. hashValue는 앞부분 일치, page는 0부터, size 상한은 200.
export type WhitelistQuery = {
  hashValue?: string
  origin?: WhitelistOrigin
  isActive?: boolean
  includeExpired?: boolean
  page?: number
  size?: number
}

export type CreateWhitelistRequest = {
  sha256: string
  reason: string
  expiresAt?: string
}

export function getWhitelist(query: WhitelistQuery = {}) {
  return apiRequest<PageResponse<WhitelistItem>>(
    '/api/admin/whitelist' + toQueryString(query),
  )
}

export function createWhitelist(body: CreateWhitelistRequest) {
  return apiRequest<WhitelistItem>('/api/admin/whitelist', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function updateWhitelistReason(sha256: string, reason: string) {
  return apiRequest<WhitelistItem>('/api/admin/whitelist/' + encodeURIComponent(sha256), {
    method: 'PATCH',
    body: JSON.stringify({ reason }),
  })
}

export function removeWhitelist(sha256: string) {
  return apiRequest<void>('/api/admin/whitelist/' + encodeURIComponent(sha256), {
    method: 'DELETE',
  })
}

export type BlacklistSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
export type BlacklistSource = 'MANUAL' | 'IMPORT'

export type BlacklistItem = {
  hashType: 'SHA256'
  hashValue: string
  reason: string
  severity: BlacklistSeverity
  source: BlacklistSource
  addedBy: AdminSummary | null
  isActive: boolean
  createdAt: string
  deactivatedAt: string | null
  updatedAt?: string
}

export type BlacklistQuery = {
  hashValue?: string
  hashType?: 'SHA256' | 'TLSH'
  severity?: string
  source?: BlacklistSource
  isActive?: boolean
  cursor?: string
  limit?: number
}

export type CreateBlacklistRequest = {
  sha256: string
  reason: string
}

export function getBlacklist(query: BlacklistQuery = {}) {
  return apiRequest<CursorPage<BlacklistItem>>(
    '/api/admin/blacklist' + toQueryString(query),
  )
}

export function createBlacklist(body: CreateBlacklistRequest) {
  return apiRequest<BlacklistItem>('/api/admin/blacklist', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function updateBlacklistReason(sha256: string, reason: string) {
  return apiRequest<BlacklistItem>('/api/admin/blacklist/' + encodeURIComponent(sha256), {
    method: 'PATCH',
    body: JSON.stringify({ reason }),
  })
}

export function removeBlacklist(sha256: string) {
  return apiRequest<void>('/api/admin/blacklist/' + encodeURIComponent(sha256), {
    method: 'DELETE',
  })
}
