import {
  getDailyBlocks,
  getDashboardSummary,
  getDashboardThreats,
  getEngineStatus,
  getTopSources,
  getUnacknowledgedBlocks,
} from '../api/dashboard'
import type {
  DailyBlocks,
  DashboardSummary,
  EngineStatus,
  StackBy,
  ThreatRow,
  TopSource,
  UnacknowledgedBlock,
} from '../api/dashboard'
import { getDownloadEvents } from '../api/events'
import type { DownloadEventListItem } from '../api/events'
import type { PageResponse } from '../api/types'
import { periodRange } from './format'
import type { PeriodKey } from './format'

const THREAT_FETCH_SIZE = 50

export type DashboardSnapshot = {
  summary: DashboardSummary
  unacknowledged: PageResponse<UnacknowledgedBlock>
  threats: ThreatRow[]
  engine: EngineStatus
  daily: DailyBlocks
  topAgents: TopSource[]
  topHosts: TopSource[]
  recentBlocks: DownloadEventListItem[]
}

export async function loadDashboard(
  q: { period: PeriodKey; stackBy: StackBy; now?: Date },
  signal: AbortSignal,
): Promise<DashboardSnapshot> {
  const { from, to } = periodRange(q.period, q.now ?? new Date())
  const [summary, unacknowledged, threats, engine, daily, topAgents, topHosts, recent] = await Promise.all([
    getDashboardSummary({ from, to }, signal),
    getUnacknowledgedBlocks({ size: 5 }, signal),
    // 표는 10줄만 보여 주지만, 매칭 룰 Top 5는 이 목록 전체에서 차단 수 순으로 고른다.
    getDashboardThreats({ from, to, size: THREAT_FETCH_SIZE }, signal),
    getEngineStatus(signal),
    getDailyBlocks({ from, to, tz: 'Asia/Seoul', stackBy: q.stackBy }, signal),
    // 차단 상위의 PC/도메인 전환은 다시 불러오지 않도록 둘 다 미리 받는다.
    getTopSources({ from, to, by: 'AGENT', size: 5 }, signal),
    getTopSources({ from, to, by: 'HOST', size: 5 }, signal),
    getDownloadEvents({ decision: ['BLOCKED', 'FAIL_CLOSE'], from, to, size: 10 }, signal),
  ])
  return {
    summary,
    unacknowledged,
    threats: threats.content,
    engine,
    daily,
    topAgents: topAgents.content,
    topHosts: topHosts.content,
    recentBlocks: recent.content,
  }
}
