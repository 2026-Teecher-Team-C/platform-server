import { apiRequest } from './client'
import type { AdminRole } from './auth'
import type { PageResponse } from './types'
import { toQueryString } from './types'

export type AuditActor = {
  adminId: string
  username: string
  role: AdminRole
}

export type AuditLogItem = {
  logId: number
  action: string
  dangerous: boolean
  actor: AuditActor | null
  targetType: string
  targetId: string
  detail: unknown
  ipAddress: string
  createdAt: string
}

// 서버 AuditLogController.list와 1:1. from은 포함, to는 제외.
export type AuditLogQuery = {
  actorId?: string
  action?: string
  targetType?: string
  from?: string
  to?: string
  dangerousOnly?: boolean
  // page는 0부터. size 상한은 서버 기준 200.
  page?: number
  size?: number
}

export function getAuditLogs(query: AuditLogQuery = {}) {
  return apiRequest<PageResponse<AuditLogItem>>(
    '/api/admin/audit-logs' + toQueryString(query),
  )
}
