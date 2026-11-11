import { afterAll, beforeAll, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { handlers } from './index'
import { getSample } from '../../lib/api/samples'
import { mockDownloadEvents } from '../data/events'
vi.mock('../http', () => ({ authorizeMockRequest: () => ({ error: null }) }))
vi.mock('../../lib/auth/tokenStore', () => ({ getAccessToken: () => null }))
const server = setupServer(...handlers, http.get('*', () => HttpResponse.json({}, { status: 404 })))
beforeAll(() => server.listen())
afterAll(() => server.close())
it('이벤트 기준 샘플의 현재 조회 계약을 제공하고 출처를 모의 데이터로 밝힌다', async () => {
  const sha = mockDownloadEvents.find(x => x.similarity?.matchedSampleSha256)?.similarity?.matchedSampleSha256
  const sample = await getSample(sha!)
  expect(sample.sha256).toBe(sha)
  expect(sample.blacklist).toMatchObject({ source: 'MANUAL', severity: 'HIGH', isActive: true })
  expect(sample.fingerprint?.provenanceDetail).toContain('Synthetic')
  expect(sample.fingerprint?.tlsh).toMatch(/^T1[0-9A-F]{70}$/)
  expect(sample).not.toHaveProperty('downloadUrl')
})
it('잘못된 원문은 400, 현재 행 없는 해시는 404이다', async () => {
  await expect(getSample(' ' + 'ab'.repeat(32))).rejects.toMatchObject({ status: 400 })
  await expect(getSample('ab'.repeat(32) + '\n')).rejects.toMatchObject({ status: 400 })
  await expect(getSample('ff'.repeat(32))).rejects.toMatchObject({ status: 404 })
})
