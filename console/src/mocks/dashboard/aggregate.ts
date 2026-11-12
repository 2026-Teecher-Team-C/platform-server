// API 명세 §0 용어 정의와 §1~§8 응답을 데이터셋에서 계산하는 순수 함수들.
// 위젯끼리 숫자가 맞도록(M2) 모든 핸들러가 여기 함수만 쓴다.
import type {
  DailyBlocks,
  DashboardSummary,
  EngineStatus,
  Severity,
  StackBy,
  ThreatRow,
  TopSource,
  UnacknowledgedBlock,
} from '../../lib/api/dashboard'
import type { DownloadDecision, DownloadEventDetail, DownloadEventListItem } from '../../lib/api/events'
import type { AnalysisRecord, DashboardDataset } from './dataset'

type Event = DownloadEventDetail | DownloadEventListItem
type Rule = AnalysisRecord['rules'][number]

const SEVERITY_RANK: Record<Severity, number> = { LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 }
const FAILURE_STATUSES = new Set<AnalysisRecord['status']>(['TIMEOUT', 'CRASH', 'OOM'])
const UNKNOWN_HOST = '(알 수 없음)'
const TOP_RULES_IN_DAILY = 5

const time = (value: string) => new Date(value).getTime()
const inPeriod = (e: Event, from: Date, to: Date) => {
  const t = time(e.createdAt)
  return t >= from.getTime() && t < to.getTime()
}

// ---- §0 용어 정의 ----

export function isBlock(e: Pick<Event, 'decision'>): boolean {
  return e.decision === 'BLOCKED' || e.decision === 'FAIL_CLOSE'
}

// 이벤트 시각 이전의 가장 최근 SUCCESS 분석. 없으면 전체 중 가장 최근 것.
function latestSuccess(ds: DashboardDataset, sha256: string, at: string): AnalysisRecord | null {
  const atMs = time(at)
  let before: AnalysisRecord | null = null
  let any: AnalysisRecord | null = null
  for (const a of ds.analyses) {
    if (a.sha256 !== sha256 || a.status !== 'SUCCESS') continue
    const t = time(a.createdAt)
    if (!any || t > time(any.createdAt)) any = a
    if (t <= atMs && (!before || t > time(before.createdAt))) before = a
  }
  return before ?? any
}

// 룰 기반 악성 판정(ENGINE·CACHE)에 걸린 룰. 원본 배열은 공유 객체라 복사해서 정렬한다.
function matchedRules(ds: DashboardDataset, e: Event): Rule[] {
  if (e.decision !== 'BLOCKED' || !e.sha256) return []
  if (e.decisionSource !== 'ENGINE' && e.decisionSource !== 'CACHE') return []
  const rules = latestSuccess(ds, e.sha256, e.createdAt)?.rules ?? []
  return [...rules].sort(
    (a, b) => SEVERITY_RANK[b.severity] - SEVERITY_RANK[a.severity] || a.ruleName.localeCompare(b.ruleName),
  )
}

export function eventSeverity(ds: DashboardDataset, e: Event): Severity | null {
  if (e.decision !== 'BLOCKED' || !e.sha256) return null
  if (e.decisionSource === 'SIMILARITY') return 'MEDIUM'
  if (e.decisionSource === 'BLACKLIST') return ds.blacklist.get(e.sha256) ?? null
  return matchedRules(ds, e)[0]?.severity ?? null
}

export function eventRules(ds: DashboardDataset, e: Event): { topRuleName: string | null; extraRuleCount: number } {
  const rules = matchedRules(ds, e)
  return { topRuleName: rules[0]?.ruleName ?? null, extraRuleCount: Math.max(rules.length - 1, 0) }
}

export function toListItem(ds: DashboardDataset, e: DownloadEventDetail): DownloadEventListItem {
  const { requestHost: _h, url: _u, bytesUploaded: _b, heldAt: _held, decidedAt: _d, similarity, ...item } = e
  return {
    ...item,
    // 상세 스냅샷은 내리지 않고 이벤트 당시 분류만 목록에 전달한다.
    similarityClassification: similarity?.classification ?? null,
    severity: eventSeverity(ds, e),
    ...eventRules(ds, e),
    acknowledged: isBlock(e) && ds.acknowledgements.has(e.eventId),
  }
}

const atLeast = (severity: Severity | null, min: Severity) =>
  severity !== null && SEVERITY_RANK[severity] >= SEVERITY_RANK[min]

const byCreatedDesc = (a: Event, b: Event) => time(b.createdAt) - time(a.createdAt)

// ---- §1 카운터 요약 ----

export function computeSummary(ds: DashboardDataset, from: Date, to: Date): DashboardSummary {
  const events = ds.events.filter((e) => inPeriod(e, from, to))
  const blocks = events.filter(isBlock)
  const malicious = blocks.filter((e) => e.decision === 'BLOCKED')
  const countSource = (source: string) => malicious.filter((e) => e.decisionSource === source).length
  const engine = countSource('ENGINE')
  const cache = countSource('CACHE')
  const blacklist = countSource('BLACKLIST')
  const inspected = events.filter((e) => e.decision !== null && e.decision !== 'BYPASSED').length
  const length = to.getTime() - from.getTime()
  const previousFrom = new Date(from.getTime() - length)
  return {
    period: { from: from.toISOString(), to: to.toISOString() },
    blocked: blocks.length,
    blockedPreviousPeriod: ds.events.filter((e) => isBlock(e) && inPeriod(e, previousFrom, from)).length,
    malicious: { total: engine + cache + blacklist, engine, cache, blacklist },
    failClose: blocks.filter((e) => e.decision === 'FAIL_CLOSE').length,
    affectedAgents: new Set(blocks.map((e) => e.agentId)).size,
    totalAgents: ds.agents.filter((a) => a.status !== 'REVOKED').length,
    inspected,
    blockRate: inspected === 0 ? 0 : blocks.length / inspected,
  }
}

// ---- §2 확인 안 한 높은 심각도 차단 (기간 필터 없음) ----

export function listUnacknowledged(ds: DashboardDataset): UnacknowledgedBlock[] {
  const rows: (UnacknowledgedBlock & { at: number })[] = []
  for (const e of ds.events) {
    if (!isBlock(e) || ds.acknowledgements.has(e.eventId)) continue
    const severity = eventSeverity(ds, e)
    if (severity === null || !atLeast(severity, 'HIGH')) continue
    rows.push({
      at: time(e.createdAt),
      eventId: e.eventId,
      createdAt: e.createdAt,
      agentId: e.agentId,
      hostname: e.hostname,
      filename: e.filename,
      reasonType: e.decisionSource === 'BLACKLIST' ? 'BLACKLIST' : 'RULE',
      ...eventRules(ds, e),
      severity,
    })
  }
  return rows.sort((a, b) => b.at - a.at).map(({ at: _at, ...row }) => row)
}

// ---- §4 위협별 현황 ----

export function computeThreats(ds: DashboardDataset, from: Date, to: Date, size: number): ThreatRow[] {
  type Acc = { row: ThreatRow; agents: Set<string>; first: number; last: number }
  const rows = new Map<string, Acc>()
  const add = (key: string, kind: ThreatRow['kind'], ruleName: string | null, severity: Severity, e: Event) => {
    const t = time(e.createdAt)
    let acc = rows.get(key)
    if (!acc) {
      const row: ThreatRow = { kind, ruleName, severity, affectedAgents: 0, blockCount: 0, firstSeenAt: '', lastSeenAt: '' }
      acc = { row, agents: new Set(), first: t, last: t }
      rows.set(key, acc)
    }
    acc.row.blockCount += 1
    acc.agents.add(e.agentId)
    if (SEVERITY_RANK[severity] > SEVERITY_RANK[acc.row.severity]) acc.row.severity = severity
    acc.first = Math.min(acc.first, t)
    acc.last = Math.max(acc.last, t)
  }
  for (const e of ds.events) {
    if (e.decision !== 'BLOCKED' || !inPeriod(e, from, to)) continue
    if (e.decisionSource === 'BLACKLIST') {
      const severity = eventSeverity(ds, e)
      if (severity) add('\u0000BLACKLIST', 'BLACKLIST', null, severity, e)
      continue
    }
    for (const r of matchedRules(ds, e)) add(r.ruleName, 'RULE', r.ruleName, r.severity, e)
  }
  return [...rows.values()]
    .sort((a, b) => b.last - a.last || (a.row.ruleName ?? '').localeCompare(b.row.ruleName ?? ''))
    .slice(0, size)
    .map(({ row, agents, first, last }) => ({
      ...row,
      affectedAgents: agents.size,
      firstSeenAt: new Date(first).toISOString(),
      lastSeenAt: new Date(last).toISOString(),
    }))
}

// ---- §5 탐지 엔진 상태 ----

export function computeEngineStatus(ds: DashboardDataset, now: Date, windowMinutes = 10): EngineStatus {
  const relevant = ds.analyses
    .filter((a) => a.status !== 'UNSUPPORTED' && time(a.createdAt) <= now.getTime())
    .sort((a, b) => time(b.createdAt) - time(a.createdAt))
  const latestSuccessRec = relevant.find((a) => a.status === 'SUCCESS')
  const latestFailure = relevant.find((a) => FAILURE_STATUSES.has(a.status))
  const windowStart = now.getTime() - windowMinutes * 60_000
  const latestInWindow = relevant[0] && time(relevant[0].createdAt) >= windowStart ? relevant[0] : null

  const base: EngineStatus = {
    state: 'IDLE',
    since: null,
    lastSuccessAt: latestSuccessRec?.createdAt ?? null,
    lastFailureAt: latestFailure?.createdAt ?? null,
    windowMinutes,
    failCloseSinceDown: 0,
    affectedAgentsSinceDown: 0,
  }
  if (!latestInWindow) return base
  if (latestInWindow.status === 'SUCCESS') return { ...base, state: 'HEALTHY' }

  // 가장 최근 기록이 실패 = 마지막 실패 뒤에 SUCCESS가 없다.
  const successMs = latestSuccessRec ? time(latestSuccessRec.createdAt) : -Infinity
  const failuresSince = relevant.filter((a) => FAILURE_STATUSES.has(a.status) && time(a.createdAt) > successMs)
  const since = failuresSince[failuresSince.length - 1].createdAt
  const sinceMs = time(since)
  const failCloses = ds.events.filter(
    (e) => e.decision === 'FAIL_CLOSE' && time(e.createdAt) >= sinceMs && time(e.createdAt) <= now.getTime(),
  )
  return {
    ...base,
    state: 'DOWN',
    since,
    failCloseSinceDown: failCloses.length,
    affectedAgentsSinceDown: new Set(failCloses.map((e) => e.agentId)).size,
  }
}

// ---- §6 일별 차단 ----

function dayKeys(from: Date, to: Date, format: (d: Date) => string): string[] {
  const first = format(from)
  const last = format(new Date(Math.max(to.getTime() - 1, from.getTime())))
  const keys: string[] = []
  // 'YYYY-MM-DD'를 UTC 달력 날짜로 보고 하루씩 더한다(시간대·DST와 무관).
  for (let d = new Date(`${first}T00:00:00Z`); ; d = new Date(d.getTime() + 86_400_000)) {
    const key = d.toISOString().slice(0, 10)
    keys.push(key)
    if (key >= last) break
  }
  return keys
}

export function computeDailyBlocks(
  ds: DashboardDataset,
  from: Date,
  to: Date,
  tz: string,
  stackBy: StackBy,
): DailyBlocks {
  const formatter = new Intl.DateTimeFormat('en-CA', { timeZone: tz })
  const format = (d: Date) => formatter.format(d)
  const blocks = ds.events.filter((e) => isBlock(e) && inPeriod(e, from, to))

  let keys: string[]
  let keyOf: (e: Event) => string | null
  if (stackBy === 'SOURCE') {
    // 새 판정 소스가 실제 존재하면 별도 버킷으로 보존한다. TLSH를 룰 악성 통계에 합쳐 해석하지 않는다.
    keys = ['ENGINE', 'CACHE', 'BLACKLIST', ...(blocks.some(e => e.decisionSource === 'SIMILARITY') ? ['SIMILARITY'] : []), 'FAIL_CLOSE']
    keyOf = (e) => (e.decision === 'FAIL_CLOSE' ? 'FAIL_CLOSE' : e.decisionSource)
  } else {
    // 이벤트 하나는 대표 룰 한 칸에만 센다(막대 합 = 차단 수).
    const bucket = (e: Event) => {
      if (e.decision === 'FAIL_CLOSE') return 'FAIL_CLOSE'
      if (e.decisionSource === 'BLACKLIST') return 'BLACKLIST'
      return eventRules(ds, e).topRuleName
    }
    const ruleCounts = new Map<string, number>()
    for (const e of blocks) {
      const key = bucket(e)
      if (key !== null && key !== 'FAIL_CLOSE' && key !== 'BLACKLIST') ruleCounts.set(key, (ruleCounts.get(key) ?? 0) + 1)
    }
    const topRules = [...ruleCounts.entries()]
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
      .slice(0, TOP_RULES_IN_DAILY)
      .map(([name]) => name)
    const top = new Set(topRules)
    keys = [...topRules, 'BLACKLIST', 'OTHER', 'FAIL_CLOSE']
    keyOf = (e) => {
      const key = bucket(e)
      return key === 'FAIL_CLOSE' || key === 'BLACKLIST' || (key !== null && top.has(key)) ? key : 'OTHER'
    }
  }

  const days = dayKeys(from, to, format).map((date) => ({
    date,
    counts: Object.fromEntries(keys.map((k) => [k, 0])) as Record<string, number>,
  }))
  const byDate = new Map(days.map((d) => [d.date, d.counts]))
  for (const e of blocks) {
    const counts = byDate.get(format(new Date(e.createdAt)))
    const key = keyOf(e)
    if (counts && key !== null && key in counts) counts[key] += 1
  }
  return { stackBy, keys, days }
}

// ---- §7 차단 상위 PC / 도메인 ----

export function computeTopSources(
  ds: DashboardDataset,
  from: Date,
  to: Date,
  by: 'AGENT' | 'HOST',
  size: number | null,
): TopSource[] {
  const rows = new Map<string, TopSource>()
  for (const e of ds.events) {
    if (!isBlock(e) || !inPeriod(e, from, to)) continue
    const host = ('requestHost' in e && e.requestHost) || UNKNOWN_HOST
    const [key, label] = by === 'AGENT' ? [e.agentId, e.hostname] : [host, host]
    const row = rows.get(key) ?? { key, label, blockCount: 0 }
    row.blockCount += 1
    rows.set(key, row)
  }
  const sorted = [...rows.values()].sort((a, b) => b.blockCount - a.blockCount || a.label.localeCompare(b.label))
  return size === null ? sorted : sorted.slice(0, size)
}

// ---- §8 다운로드 이벤트 목록 ----

export function filterEvents(
  ds: DashboardDataset,
  q: {
    decisions?: DownloadDecision[]
    agentId?: string
    from?: Date
    to?: Date
    minSeverity?: Severity
    acknowledged?: boolean
  },
): DownloadEventListItem[] {
  return ds.events
    .filter((e) => {
      if (q.decisions && q.decisions.length > 0 && (e.decision === null || !q.decisions.includes(e.decision))) return false
      if (q.agentId && e.agentId !== q.agentId) return false
      if (q.from && time(e.createdAt) < q.from.getTime()) return false
      if (q.to && time(e.createdAt) >= q.to.getTime()) return false
      return true
    })
    .map((e) => toListItem(ds, e))
    .filter((item) => {
      if (q.minSeverity && !atLeast(item.severity, q.minSeverity)) return false
      if (q.acknowledged !== undefined && item.acknowledged !== q.acknowledged) return false
      return true
    })
    .sort(byCreatedDesc)
}
