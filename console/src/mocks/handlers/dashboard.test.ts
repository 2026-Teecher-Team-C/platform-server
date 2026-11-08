import { afterAll, afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { dashboardHandlers } from './dashboard'
import { eventHandlers } from './events'
import { getDashboardDataset, resetDashboardDataset } from '../dashboard/store'
import { computeSummary, listUnacknowledged } from '../dashboard/aggregate'
import {
  acknowledgeDownloadEvent,
  getDashboardSummary,
  getEngineStatus,
  getUnacknowledgedBlocks,
} from '../../lib/api/dashboard'
import { getDownloadEvents } from '../../lib/api/events'
import { mockAuditLogs } from '../data/auditLogs'

// 업무 계약을 검사한다. 실제 로그인/역할 검증을 대신하는 테스트는 아니다.
vi.mock('../http', async (original) => ({ ...await original<typeof import('../http')>(), authorizeMockRequest: () => ({ error: null, account: { adminId: 'qa-admin', username: 'qa', role: 'ADMIN' } }) }))

const NOW = new Date('2026-11-08T03:00:00.000Z')
const originalAudit = mockAuditLogs.slice()
const server = setupServer(...dashboardHandlers, ...eventHandlers)
const DAY = 24 * 60 * 60 * 1000

beforeAll(() => server.listen())
afterAll(() => server.close())
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
  resetDashboardDataset({ now: NOW })
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  mockAuditLogs.splice(0, mockAuditLogs.length, ...originalAudit)
  resetDashboardDataset({ now: NOW })
})

it('확인 처리 후 미확인 목록, 이벤트 목록, 감사 로그가 함께 바뀐다', async () => {
  const before = await getUnacknowledgedBlocks({ size: 200 })
  const target = before.content[0]
  const result = await acknowledgeDownloadEvent(target.eventId)
  expect(result).toMatchObject({ eventId: target.eventId, acknowledgedBy: 'qa' })

  const after = await getUnacknowledgedBlocks({ size: 200 })
  expect(after.totalElements).toBe(before.totalElements - 1)
  const acked = await getDownloadEvents({ acknowledged: true, size: 200 })
  expect(acked.content.map((e) => e.eventId)).toContain(target.eventId)
  expect(mockAuditLogs.filter((l) => l.action === 'DOWNLOAD_EVENT_ACKNOWLEDGE')).toHaveLength(1)
})

it('같은 이벤트를 두 번 확인하면 처음 값을 돌려주고 감사 로그는 한 번만 남는다', async () => {
  const { content } = await getUnacknowledgedBlocks({ size: 5 })
  const first = await acknowledgeDownloadEvent(content[0].eventId)
  vi.setSystemTime(new Date(NOW.getTime() + 60_000))
  const second = await acknowledgeDownloadEvent(content[0].eventId)
  expect(second).toEqual(first)
  expect(mockAuditLogs.filter((l) => l.action === 'DOWNLOAD_EVENT_ACKNOWLEDGE')).toHaveLength(1)
})

it('없는 이벤트는 404, 차단이 아닌 이벤트는 409다', async () => {
  await expect(acknowledgeDownloadEvent('no-such-event')).rejects.toMatchObject({
    status: 404,
    body: { code: 'DOWNLOAD_EVENT_NOT_FOUND' },
  })
  const released = getDashboardDataset().events.find((e) => e.decision === 'RELEASED')!
  await expect(acknowledgeDownloadEvent(released.eventId)).rejects.toMatchObject({
    status: 409,
    body: { code: 'CONFLICT' },
  })
  expect(mockAuditLogs.some((l) => l.action === 'DOWNLOAD_EVENT_ACKNOWLEDGE')).toBe(false)
})

it('decision을 여러 번 주면 모두 포함한다', async () => {
  const q = { size: 200 }
  const blocked = await getDownloadEvents({ ...q, decision: ['BLOCKED'] })
  const failClose = await getDownloadEvents({ ...q, decision: ['FAIL_CLOSE'] })
  const both = await getDownloadEvents({ ...q, decision: ['BLOCKED', 'FAIL_CLOSE'] })
  expect(blocked.totalElements).toBeGreaterThan(0)
  expect(failClose.totalElements).toBeGreaterThan(0)
  expect(both.totalElements).toBe(blocked.totalElements + failClose.totalElements)
})

it('기간이 90일을 넘으면 400이다', async () => {
  const to = NOW
  const from = new Date(to.getTime() - 91 * DAY)
  await expect(
    getDashboardSummary({ from: from.toISOString(), to: to.toISOString() }),
  ).rejects.toMatchObject({ status: 400, body: { code: 'INVALID_REQUEST' } })
})

it('요약 API 응답이 집계 함수 결과와 같다', async () => {
  const to = NOW
  const from = new Date(to.getTime() - 7 * DAY)
  const res = await getDashboardSummary({ from: from.toISOString(), to: to.toISOString() })
  expect(res).toEqual(computeSummary(getDashboardDataset(), from, to))
  const unack = await getUnacknowledgedBlocks({ size: 200 })
  expect(unack.totalElements).toBe(listUnacknowledged(getDashboardDataset()).length)
})

it('mockEngine 쿼리로 엔진 시나리오를 고른다', async () => {
  vi.stubGlobal('location', { search: '?mockEngine=DOWN' })
  resetDashboardDataset()
  getDashboardDataset()
  const status = await getEngineStatus()
  expect(status.state).toBe('DOWN')
})
