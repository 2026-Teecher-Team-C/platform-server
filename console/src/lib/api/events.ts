import { apiRequest } from './client'
import type { CursorPage } from './types'
import { toQueryString } from './types'

export type DownloadDecision = 'ALLOWED' | 'BLOCKED'

export type DownloadEventListItem = {
  eventId: string
  agentId: string
  hostname: string
  sha256: string
  filename: string | null
  mimeType: string | null
  fileSize: number | null
  decision: DownloadDecision
  decisionSource: string
  cacheHit: boolean
  holdDurationMs: number | null
  createdAt: string
}

export type DownloadEventDetail = DownloadEventListItem & {
  requestHost: string
  url: string
  bytesUploaded: number
  heldAt?: string | null
  decidedAt: string
}

export type DownloadEventQuery = {
  agentId?: string
  decision?: DownloadDecision
  from?: string
  to?: string
  cursor?: string
  limit?: number
}

export function getDownloadEvents(query: DownloadEventQuery = {}) {
  return apiRequest<CursorPage<DownloadEventListItem>>(
    '/api/admin/download-events' + toQueryString(query),
  )
}

export function getDownloadEvent(eventId: string) {
  return apiRequest<DownloadEventDetail>(
    '/api/admin/download-events/' + encodeURIComponent(eventId),
  )
}
