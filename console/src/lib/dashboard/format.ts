import type { Severity } from '../api/dashboard'
import type { DownloadDecision, DownloadDecisionSource } from '../api/events'

export type PeriodKey = 'today' | '7d' | '30d'

export const PERIOD_OPTIONS: { key: PeriodKey; label: string }[] = [
  { key: 'today', label: '오늘' },
  { key: '7d', label: '최근 7일' },
  { key: '30d', label: '최근 30일' },
]

const DAY_MS = 24 * 60 * 60 * 1000
const MINUS = '−'

export function periodRange(key: PeriodKey, now: Date): { from: string; to: string } {
  const to = now.toISOString()
  if (key === 'today') {
    const start = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    return { from: start.toISOString(), to }
  }
  const days = key === '7d' ? 7 : 30
  return { from: new Date(now.getTime() - days * DAY_MS).toISOString(), to }
}

export function previousPeriodLabel(key: PeriodKey): string {
  if (key === 'today') return '전날 대비'
  if (key === '7d') return '전주 대비'
  return '이전 30일 대비'
}

export function formatDelta(current: number, previous: number): string {
  const diff = current - previous
  if (diff === 0) return '변화 없음'
  return diff > 0 ? `+${diff}` : `${MINUS}${Math.abs(diff)}`
}

export function formatRate(rate: number): string {
  if (!Number.isFinite(rate)) return '0%'
  return `${(rate * 100).toFixed(1)}%`
}

export const SEVERITY_LABEL: Record<Severity, string> = {
  CRITICAL: 'Critical',
  HIGH: 'High',
  MEDIUM: 'Medium',
  LOW: 'Low',
}

export function severityTone(s: Severity | null): 'danger' | 'warning' | 'neutral' {
  if (s === 'CRITICAL' || s === 'HIGH') return 'danger'
  if (s === 'MEDIUM') return 'warning'
  return 'neutral'
}

const pad = (n: number) => String(n).padStart(2, '0')

export function formatClock(iso: string): string {
  const d = new Date(iso)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatMonthDay(iso: string): string {
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()}`
}

function dayStart(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

export function formatRelative(iso: string, now: Date): string {
  const t = new Date(iso)
  const diff = now.getTime() - t.getTime()
  if (diff < 0) return '방금'
  const minutes = Math.floor(diff / 60000)
  if (minutes < 60) return minutes === 0 ? '방금' : `${minutes}분 전`
  const dayDiff = Math.round((dayStart(now).getTime() - dayStart(t).getTime()) / DAY_MS)
  if (dayDiff === 0) return formatClock(iso)
  if (dayDiff === 1) return '어제'
  return formatMonthDay(iso)
}

export function blockReasonText(item: {
  reasonType?: 'RULE' | 'BLACKLIST'
  decision?: DownloadDecision | null
  decisionSource?: DownloadDecisionSource | null
  topRuleName: string | null
  extraRuleCount: number
}): string {
  if (item.decision === 'FAIL_CLOSE') return '검사 불가'
  if (item.reasonType === 'BLACKLIST' || item.decisionSource === 'BLACKLIST') {
    return '관리자 등록 블랙리스트'
  }
  if (item.topRuleName) {
    return item.extraRuleCount > 0
      ? `${item.topRuleName} 외 ${item.extraRuleCount}건`
      : item.topRuleName
  }
  return '룰 정보 없음'
}
