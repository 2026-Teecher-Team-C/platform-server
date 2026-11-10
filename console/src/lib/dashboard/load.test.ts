import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as dash from '../api/dashboard'
import * as events from '../api/events'
import { loadDashboard } from './load'

vi.mock('../api/dashboard')
vi.mock('../api/events')

const now = new Date(2026, 10, 8, 14, 2)
const page = { content: [{ eventId: 'e' }], page: 0, size: 5, totalElements: 1, totalPages: 1 }

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(dash.getDashboardSummary).mockResolvedValue({ blocked: 1 } as never)
  vi.mocked(dash.getUnacknowledgedBlocks).mockResolvedValue(page as never)
  vi.mocked(dash.getDashboardThreats).mockResolvedValue({ content: [{ ruleName: 'r' }] } as never)
  vi.mocked(dash.getEngineStatus).mockResolvedValue({ state: 'HEALTHY' } as never)
  vi.mocked(dash.getDailyBlocks).mockResolvedValue({ days: [] } as never)
  vi.mocked(dash.getTopSources).mockResolvedValue({ by: 'AGENT', content: [{ key: 'a' }] } as never)
  vi.mocked(events.getDownloadEvents).mockResolvedValue({ content: [{ eventId: 'x' }] } as never)
})

describe('loadDashboard', () => {
  it('7개 요청을 같은 기간과 signal로 한 번씩 부른다', async () => {
    const signal = new AbortController().signal
    const snap = await loadDashboard({ period: '7d', stackBy: 'RULE', now }, signal)
    const from = new Date(now.getTime() - 7 * 86400000).toISOString()
    const to = now.toISOString()

    expect(dash.getDashboardSummary).toHaveBeenCalledOnce()
    expect(dash.getDashboardSummary).toHaveBeenCalledWith({ from, to }, signal)
    expect(dash.getUnacknowledgedBlocks).toHaveBeenCalledWith({ size: 5 }, signal)
    expect(dash.getDashboardThreats).toHaveBeenCalledWith({ from, to, size: 10 }, signal)
    expect(dash.getEngineStatus).toHaveBeenCalledWith(signal)
    expect(dash.getDailyBlocks).toHaveBeenCalledWith(
      { from, to, tz: 'Asia/Seoul', stackBy: 'RULE' },
      signal,
    )
    expect(dash.getTopSources).toHaveBeenCalledWith({ from, to, by: 'AGENT', size: 5 }, signal)
    expect(events.getDownloadEvents).toHaveBeenCalledWith(
      { decision: ['BLOCKED', 'FAIL_CLOSE'], from, to, size: 10 },
      signal,
    )
    for (const fn of [
      dash.getUnacknowledgedBlocks,
      dash.getDashboardThreats,
      dash.getEngineStatus,
      dash.getDailyBlocks,
      dash.getTopSources,
      events.getDownloadEvents,
    ]) {
      expect(fn).toHaveBeenCalledOnce()
    }

    expect(snap.threats).toEqual([{ ruleName: 'r' }])
    expect(snap.topAgents).toEqual([{ key: 'a' }])
    expect(snap.recentBlocks).toEqual([{ eventId: 'x' }])
    expect(snap.unacknowledged).toBe(page)
    expect(snap.engine.state).toBe('HEALTHY')
  })

  it('하나가 실패하면 loadDashboard도 실패한다', async () => {
    vi.mocked(dash.getEngineStatus).mockRejectedValue(new Error('boom'))
    await expect(
      loadDashboard({ period: 'today', stackBy: 'SOURCE', now }, new AbortController().signal),
    ).rejects.toThrow('boom')
  })
})
