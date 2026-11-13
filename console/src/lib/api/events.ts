import { apiRequest } from './client'
import type { Severity } from './dashboard'
import type { PageResponse } from './types'
import { toQueryString } from './types'

// 실제 DB CHECK 제약(ck_download_events_decision/ck_download_events_decision_source)과 1:1.
export type DownloadDecision = 'RELEASED' | 'BLOCKED' | 'BYPASSED' | 'FAIL_CLOSE'
export type DownloadDecisionSource = 'WHITELIST' | 'BLACKLIST' | 'CACHE' | 'ENGINE' | 'POLICY' | 'SIMILARITY'

export type SimilarityClassification = 'BLOCK' | 'WARN' | 'NONE' | 'UNAVAILABLE'
// 이벤트 FK에 연결된 당시 평가다. null은 기록 없음, NONE은 평가 완료, UNAVAILABLE은 계산 불가다.
export type EventSimilarity = {
  classification: SimilarityClassification
  matchedSampleSha256: string | null
  distance: number | null
  assessedAt: string
}

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
  similarityClassification: SimilarityClassification | null
  // 이벤트 심각도·대표 룰(API 명세 §0, §8). 차단이 아니거나 FAIL_CLOSE면 severity/topRuleName은 null이다.
  severity: Severity | null
  topRuleName: string | null
  extraRuleCount: number
  acknowledged: boolean
}

export type DownloadEventDetail = Omit<DownloadEventListItem, 'similarityClassification'> & {
  requestHost: string
  url: string
  bytesUploaded: number
  heldAt?: string | null
  decidedAt: string | null
  similarity: EventSimilarity | null
}

export type DownloadEventQuery = {
  agentId?: string
  // 배열이면 decision=A&decision=B 로 보낸다.
  decision?: DownloadDecision | DownloadDecision[]
  minSeverity?: Severity
  acknowledged?: boolean
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
