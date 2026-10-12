import { apiRequest } from './client'
import { toQueryString } from './types'

/**
 * Provisional S4 contract. The current Notion spec marks the stats response
 * example as "추정 — 확인 필요"; keep this isolated until B/C metric agreement.
 */
export type StatsSummary = {
  period: {
    from: string
    to: string
  }
  totalEvents: number
  blockedCount: number
  allowedCount: number
  cacheHitRate: number
  holdDurationP99Ms: number
  totalBytesUploaded: number
  bandwidthSavedRatio: number
  inspectionFailureRate?: number
}

export type StatsQuery = {
  from?: string
  to?: string
  granularity?: 'HOUR' | 'DAY'
}

export function getStats(query: StatsQuery = {}) {
  return apiRequest<StatsSummary>('/api/admin/stats' + toQueryString(query))
}
