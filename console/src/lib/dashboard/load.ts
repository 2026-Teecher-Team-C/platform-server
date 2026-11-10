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

export type DashboardSnapshot = {
  summary: DashboardSummary
  unacknowledged: PageResponse<UnacknowledgedBlock>
  threats: ThreatRow[]
  engine: EngineStatus
  daily: DailyBlocks
  topAgents: TopSource[]
  recentBlocks: DownloadEventListItem[]
}

export async function loadDashboard(
  q: { period: PeriodKey; stackBy: StackBy; now?: Date },
  signal: AbortSignal,
): Promise<DashboardSnapshot> {
  const { from, to } = periodRange(q.period, q.now ?? new Date())
  const [summary, unacknowledged, threats, engine, daily, topAgents, recent] = await Promise.all([
    getDashboardSummary({ from, to }, signal),
    getUnacknowledgedBlocks({ size: 5 }, signal),
    getDashboardThreats({ from, to, size: 10 }, signal),
    getEngineStatus(signal),
    getDailyBlocks({ from, to, tz: 'Asia/Seoul', stackBy: q.stackBy }, signal),
    getTopSources({ from, to, by: 'AGENT', size: 5 }, signal),
    getDownloadEvents({ decision: ['BLOCKED', 'FAIL_CLOSE'], from, to, size: 10 }, signal),
  ])
  return {
    summary,
    unacknowledged,
    threats: threats.content,
    engine,
    daily,
    topAgents: topAgents.content,
    recentBlocks: recent.content,
  }
}
