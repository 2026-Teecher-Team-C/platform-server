import { afterAll, beforeAll, expect, it, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { eventHandlers } from './events'
import { getDownloadEvent, getDownloadEvents } from '../../lib/api/events'
vi.mock('../http', () => ({ authorizeMockRequest: () => ({ error: null }) }))
vi.mock(import('../../lib/auth/tokenStore'), async (original) => ({ ...await original(), getAccessToken: () => null }))
const server = setupServer(...eventHandlers)
beforeAll(() => server.listen())
afterAll(() => server.close())
it('실제 REST와 같이 상세 근거, 목록 분류를 분리하고 기존 4개 fixture를 유지한다', async () => {
  const page = await getDownloadEvents({ from: '2026-10-10T00:00:00Z', to: '2026-10-12T00:00:00Z', size: 200 })
  expect(page.content.slice(0, 4).map(x => x.eventId)).toEqual([1, 2, 3, 4].map(n => '30000000-0000-4000-8000-' + String(n).padStart(12, '0')))
  expect(new Set(page.content.map(x => x.similarityClassification))).toEqual(new Set([null, 'BLOCK', 'WARN', 'NONE', 'UNAVAILABLE']))
  for (const row of page.content) {
    expect(row).not.toHaveProperty('similarity')
    const detail = await getDownloadEvent(row.eventId)
    expect(detail).toHaveProperty('similarity')
    expect(detail).not.toHaveProperty('similarityClassification')
    expect(detail.similarity?.classification ?? null).toBe(row.similarityClassification)
    if (detail.similarity) {
      expect(new Date(detail.similarity.assessedAt).getTime()).toBeLessThanOrEqual(new Date(detail.decidedAt!).getTime())
      expect(new Date(detail.decidedAt!).getTime()).toBeLessThanOrEqual(new Date(detail.createdAt).getTime())
    }
    if (detail.similarity?.classification === 'WARN') expect(detail.decision).toBe('RELEASED')
  }
})
