import { apiRequest } from './client'
import type { PageResponse } from './types'
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
  // 판정 전(HELD 등) 이벤트도 조회되므로 DB의 nullable 컬럼을 그대로 반영한다.
  decision: DownloadDecision | null
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
  decidedAt: string | null
}

export type DownloadEventQuery = {
  agentId?: string
  decision?: DownloadDecision
  from?: string
  to?: string
  // page는 0부터. size 상한은 서버 기준 200.
  page?: number
  size?: number
}

export function getDownloadEvents(query: DownloadEventQuery = {}, signal?: AbortSignal) {
  return apiRequest<PageResponse<DownloadEventListItem>>(
    '/api/admin/download-events' + toQueryString(query),
    { signal },
  )
}

export function getDownloadEvent(eventId: string) {
  return apiRequest<DownloadEventDetail>(
    '/api/admin/download-events/' + encodeURIComponent(eventId),
  )
}
