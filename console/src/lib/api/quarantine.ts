import { apiRequest } from './client'
import type { CursorPage } from './types'
import { toQueryString } from './types'

export type QuarantineStatus = 'QUARANTINED' | 'RESTORED'

export type QuarantineListItem = {
  quarantineId: string
  eventId: string
  agentId: string
  hostname: string
  sha256: string
  originalFilename: string
  fileSize: number
  status: QuarantineStatus
  quarantinedAt: string
  restoredAt: string | null
}

export type QuarantineQuery = {
  status?: QuarantineStatus
  agentId?: string
  cursor?: string
  limit?: number
}

export type RestoreQuarantineResponse = {
  quarantineId: string
  sha256: string
  status: 'RESTORED'
  whitelisted: true
  restoredAt: string
}

export function getQuarantineFiles(query: QuarantineQuery = {}) {
  return apiRequest<CursorPage<QuarantineListItem>>(
    '/api/admin/quarantine' + toQueryString(query),
  )
}

export function getQuarantineFile(quarantineId: string) {
  return apiRequest<QuarantineListItem & {
    downloadEvent?: {
      eventId: string
      requestHost: string
      url: string
    }
    agent?: {
      agentId: string
      hostname: string
    }
  }>('/api/admin/quarantine/' + encodeURIComponent(quarantineId))
}

export function restoreQuarantineFile(quarantineId: string) {
  return apiRequest<RestoreQuarantineResponse>(
    '/api/admin/quarantine/' + encodeURIComponent(quarantineId) + '/restore',
    { method: 'POST' },
  )
}
