import { apiRequest } from './client'
import type { AdminRole } from './auth'
import type { CursorPage } from './types'
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

export type AuditLogQuery = {
  actorId?: string
  action?: string
  targetType?: string
  from?: string
  to?: string
  dangerousOnly?: boolean
  cursor?: string
  limit?: number
}

export function getAuditLogs(query: AuditLogQuery = {}) {
  return apiRequest<CursorPage<AuditLogItem>>(
    '/api/admin/audit-logs' + toQueryString(query),
  )
}
