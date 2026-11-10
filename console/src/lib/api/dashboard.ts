import { apiRequest } from './client'
import type { PageResponse } from './types'
import { toQueryString } from './types'

export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'

// from/to는 ISO-8601 instant, [from, to) 반열린 구간.
export type PeriodQuery = { from: string; to: string }

export type DashboardSummary = {
  period: { from: string; to: string }
  blocked: number
  blockedPreviousPeriod: number
  malicious: { total: number; engine: number; cache: number; blacklist: number }
  failClose: number
  affectedAgents: number
  totalAgents: number
  inspected: number
  blockRate: number
}

export type UnacknowledgedBlock = {
  eventId: string
  createdAt: string
  agentId: string
  hostname: string
  filename: string | null
  reasonType: 'RULE' | 'BLACKLIST'
  topRuleName: string | null
  extraRuleCount: number
  severity: Severity
}

export type AcknowledgeResult = {
  eventId: string
  acknowledgedBy: string
  acknowledgedAt: string
}

export type ThreatRow = {
  kind: 'RULE' | 'BLACKLIST'
  ruleName: string | null
  severity: Severity
  affectedAgents: number
  blockCount: number
  firstSeenAt: string
  lastSeenAt: string
}

export type EngineState = 'HEALTHY' | 'DOWN' | 'IDLE'

export type EngineStatus = {
  state: EngineState
  since: string | null
  lastSuccessAt: string | null
  lastFailureAt: string | null
  windowMinutes: number
  failCloseSinceDown: number
  affectedAgentsSinceDown: number
}

export type StackBy = 'SOURCE' | 'RULE'

export type DailyBlocks = {
  stackBy: StackBy
  keys: string[]
  days: { date: string; counts: Record<string, number> }[]
}

export type TopSource = { key: string; label: string; blockCount: number }

export function getDashboardSummary(q: PeriodQuery, signal?: AbortSignal) {
  return apiRequest<DashboardSummary>('/api/admin/dashboard/summary' + toQueryString(q), { signal })
}

export function getUnacknowledgedBlocks(
  q: { page?: number; size?: number } = {},
  signal?: AbortSignal,
) {
  return apiRequest<PageResponse<UnacknowledgedBlock>>(
    '/api/admin/dashboard/unacknowledged-blocks' + toQueryString(q),
    { signal },
  )
}

export function acknowledgeDownloadEvent(eventId: string) {
  return apiRequest<AcknowledgeResult>(
    '/api/admin/download-events/' + encodeURIComponent(eventId) + '/acknowledge',
    { method: 'POST' },
  )
}

export function getDashboardThreats(q: PeriodQuery & { size?: number }, signal?: AbortSignal) {
  return apiRequest<{ content: ThreatRow[] }>('/api/admin/dashboard/threats' + toQueryString(q), {
    signal,
  })
}

export function getEngineStatus(signal?: AbortSignal) {
  return apiRequest<EngineStatus>('/api/admin/dashboard/engine-status', { signal })
}

export function getDailyBlocks(
  q: PeriodQuery & { tz?: string; stackBy: StackBy },
  signal?: AbortSignal,
) {
  return apiRequest<DailyBlocks>('/api/admin/dashboard/daily-blocks' + toQueryString(q), { signal })
}

export function getTopSources(
  q: PeriodQuery & { by: 'AGENT' | 'HOST'; size?: number },
  signal?: AbortSignal,
) {
  return apiRequest<{ by: 'AGENT' | 'HOST'; content: TopSource[] }>(
    '/api/admin/dashboard/top-sources' + toQueryString(q),
    { signal },
  )
}
