import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { policyHandlers } from './policies'
import { API_BASE_URL } from '../../lib/api/client'
import { mockBypassDomains } from '../data/policies'
import { createBypassDomain, deactivateBypassDomain, getBypassDomains } from '../../lib/api/policies'

// 인증 자체가 아닌 목록 계약을 검증한다. 인증/401 흐름은 공통 인증 모듈의 책임이다.
vi.mock('../http', () => ({ authorizeMockRequest: () => ({ account: {}, error: null }) }))
vi.mock('../../lib/auth/tokenStore', () => ({ getAccessToken: () => null }))
const server = setupServer(...policyHandlers)
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterAll(() => server.close())

describe('바이패스 쓰기 계약, domain client부터 MSW까지', () => {
  it('randomUUID가 없는 HTTP 환경에서도 유효한 UUID로 등록하고 비활성화한다', async () => {
    const snapshot = structuredClone(mockBypassDomains)
    const getRandomValues = crypto.getRandomValues.bind(crypto)
    vi.stubGlobal('crypto', { getRandomValues })
    try {
      const created = await createBypassDomain({ host: 'http-qa.example.com', category: 'PINNED', reason: 'HTTP 개발 환경 검증' })
      expect(created.bypassId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
      await deactivateBypassDomain(created.bypassId)
      expect((await getBypassDomains({ host: created.host, isActive: false })).content[0].bypassId).toBe(created.bypassId)
    } finally { vi.unstubAllGlobals(); mockBypassDomains.splice(0, mockBypassDomains.length, ...snapshot) }
  })

  it('201 등록, 409 중복, 204 비활성화, 404 재해제, 같은 행 재등록을 따른다', async () => {
    const snapshot = structuredClone(mockBypassDomains)
    try {
      const created = await createBypassDomain({ host: ' QA.Contract.Example. ', category: 'PINNED', reason: ' 원문 사유 ' })
      expect(created).toMatchObject({ host: 'qa.contract.example', category: 'PINNED', isActive: true, reason: ' 원문 사유 ' })
      expect(created).not.toHaveProperty('updatedAt')
      expect(created.bypassId).toMatch(/^[0-9a-f-]{36}$/)
      await expect(createBypassDomain({ host: created.host, category: 'PINNED', reason: '중복' })).rejects.toMatchObject({ status: 409, body: { code: 'CONFLICT' } })
      // 204는 JSON이 없는 응답이어도 공통 client가 성공 처리해야 한다.
      await deactivateBypassDomain(created.bypassId)
      expect((await getBypassDomains({ isActive: false })).content).toContainEqual({ ...created, isActive: false })
      await expect(deactivateBypassDomain(created.bypassId)).rejects.toMatchObject({ status: 404, body: { code: 'NOT_FOUND' } })
      const revived = await createBypassDomain({ host: created.host, category: 'SECURITY_UPDATE', reason: '변경 사유' })
      expect(revived).toEqual({ ...created, category: 'SECURITY_UPDATE', reason: '변경 사유' })
      const response = await fetch(API_BASE_URL + '/api/admin/bypass-domains', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ host: 'status.example', category: 'PINNED', reason: '상태 확인' }) })
      expect(response.status).toBe(201)
    } finally { mockBypassDomains.splice(0, mockBypassDomains.length, ...snapshot) }
  })

  it('잘못된 UUID는 400, 없는 UUID는 404다', async () => {
    await expect(deactivateBypassDomain('not-uuid')).rejects.toMatchObject({ status: 400 })
    await expect(deactivateBypassDomain('ffffffff-ffff-4fff-8fff-ffffffffffff')).rejects.toMatchObject({ status: 404 })
  })

  it.each([
    { host: '*.example.com', category: 'PINNED', reason: '사유' },
    { host: 'a.com', category: 'pinned', reason: '사유' },
    { host: 'a.com', category: 'PINNED', reason: ' ' },
    {}, { host: 12, category: 'PINNED', reason: '사유' },
  ])('서버 입력 검증 실패를 400으로 돌려준다: %j', async (input) => {
    const response = await fetch(API_BASE_URL + '/api/admin/bypass-domains', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) })
    expect(response.status).toBe(400)
    expect(await response.json()).toMatchObject({ code: 'INVALID_INPUT' })
  })
})

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
