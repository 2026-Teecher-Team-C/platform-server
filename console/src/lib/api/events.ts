import { apiRequest } from './client'
import type { CursorPage } from './types'
import { toQueryString } from './types'

// 실제 DB CHECK 제약(ck_download_events_decision/ck_download_events_decision_source)과 1:1.
export type DownloadDecision = 'RELEASED' | 'BLOCKED' | 'BYPASSED' | 'FAIL_CLOSE'
export type DownloadDecisionSource = 'WHITELIST' | 'BLACKLIST' | 'CACHE' | 'ENGINE' | 'POLICY'

export type DownloadEventListItem = {
  eventId: string
  agentId: string
  hostname: string
  // 해시 전에 끝난 판정(예: FAIL_CLOSE)은 sha256/decisionSource가 비어 있다.
  sha256: string | null
  filename: string | null
  mimeType: string | null
  fileSize: number | null
  decision: DownloadDecision
  decisionSource: DownloadDecisionSource | null
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

export function getDownloadEvents(query: DownloadEventQuery = {}, signal?: AbortSignal) {
  return apiRequest<CursorPage<DownloadEventListItem>>(
    '/api/admin/download-events' + toQueryString(query),
    { signal },
  )
}

export function getDownloadEvent(eventId: string) {
  return apiRequest<DownloadEventDetail>(
    '/api/admin/download-events/' + encodeURIComponent(eventId),
  )
}
