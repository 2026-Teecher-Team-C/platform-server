import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { policyHandlers } from './policies'
import { API_BASE_URL } from '../../lib/api/client'
import { mockBypassDomains } from '../data/policies'
import { getBypassDomains } from '../../lib/api/policies'

// 인증 자체가 아닌 목록 계약을 검증한다. 인증/401 흐름은 공통 인증 모듈의 책임이다.
vi.mock('../http', () => ({ authorizeMockRequest: () => ({ account: {}, error: null }) }))
vi.mock('../../lib/auth/tokenStore', () => ({ getAccessToken: () => null }))
const server = setupServer(...policyHandlers)
beforeAll(() => server.listen())
afterAll(() => server.close())

describe('바이패스 도메인 mock 목록', () => {
  it('검색어는 대소문자와 앞뒤 공백을 무시하고 도메인만 검색한다', async () => {
    const data = await (await fetch(API_BASE_URL + '/api/admin/bypass-domains?host=%20PINNED%20&category=PINNED')).json()
    expect(data.totalElements).toBe(1)
    expect(data.content[0].host).toBe('pinned.example.org')
  })

  it('지원하지 않는 카테고리는 400을 반환한다', async () => {
    expect((await fetch(API_BASE_URL + '/api/admin/bypass-domains?category=INVALID')).status).toBe(400)
  })

  it('미적용 조회는 적용 목록과 분리된다', async () => {
    mockBypassDomains.push({ ...mockBypassDomains[0], bypassId: 'inactive-qa', host: 'inactive.example.test', isActive: false })
    try {
      // false 값이 쿼리에서 빠지지 않는지 실제 domain client부터 mock까지 연결해서 확인한다.
      const data = await getBypassDomains({ isActive: false, page: 0, size: 20 })
      expect(data.totalElements).toBe(1)
      expect(data.content[0].host).toBe('inactive.example.test')
    } finally {
      mockBypassDomains.pop()
    }
  })

  it('서버와 같은 PageResponse로 정렬 후 페이지를 나눈다', async () => {
    const response = await fetch(API_BASE_URL + '/api/admin/bypass-domains?page=0&size=1')
    const data = await response.json()
    expect(data).toMatchObject({ page: 0, size: 1, totalElements: 2, totalPages: 2 })
    expect(data.content[0].host).toBe('pinned.example.org')
    const next = await (await fetch(API_BASE_URL + '/api/admin/bypass-domains?page=1&size=1')).json()
    expect(next.content[0].host).toBe('updates.vendor.example')
  })
})
