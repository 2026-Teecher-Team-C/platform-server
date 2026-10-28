import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { agentHandlers } from './agents'
import { getAgents } from '../../lib/api/agents'
import { API_BASE_URL } from '../../lib/api/client'
import { mockAgents } from '../data/agents'

// 목록 계약을 확인하므로 로그인 검증은 공통 인증 테스트에 맡긴다.
vi.mock('../http', () => ({ authorizeMockRequest: () => ({ error: null }) }))
vi.mock('../../lib/auth/tokenStore', () => ({ getAccessToken: () => null }))
const server = setupServer(...agentHandlers)
beforeAll(() => server.listen())
afterAll(() => server.close())

describe('Agent 목록 API 계약', () => {
  it('같은 하트비트와 null끼리는 agentId 오름차순으로 페이지 경계를 고정한다', async () => {
    const original = mockAgents.slice()
    const ids = ['00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000004']
    mockAgents.push(...[3, 1, 2, 0].map((index) => ({ ...original[0], agentId: ids[index], hostname: 'tie-QA', lastHeartbeatAt: index < 2 ? '2030-01-01T00:00:00Z' : null })))
    try {
      const actual = []
      for (let page = 0; page < ids.length; page++) actual.push((await getAgents({ hostname: 'tie-QA', page, size: 1 })).content[0].agentId)
      expect(actual).toEqual(ids)
    } finally { mockAgents.splice(0, mockAgents.length, ...original) }
  })

  it('필터를 trim하고 결합하며 하트비트 null은 마지막으로 정렬한다', async () => {
    const original = mockAgents.slice()
    mockAgents.unshift({ ...original[0], agentId: 'fresh', hostname: 'QA_%', status: 'REVOKED', lastHeartbeatAt: null })
    mockAgents.push({ ...original[0], agentId: 'beat', hostname: 'qa_%', status: 'REVOKED' })
    try {
      const data = await getAgents({ hostname: ' QA_% ', status: 'REVOKED', osPlatform: 'WINDOWS' })
      expect(data.content.map((item) => item.agentId)).toEqual(['beat', 'fresh'])
      expect(data.content[1].lastHeartbeatAt).toBeNull()
      expect(data).toMatchObject({ page: 0, size: 20, totalElements: 2, totalPages: 1 })
    } finally { mockAgents.splice(0, mockAgents.length, ...original) }
  })

  it('잘못된 필터, 페이지 입력은 400 INVALID_INPUT이고 빈 필터는 생략한다', async () => {
    for (const query of ['status=active', 'osPlatform=NOPE', 'page=-1', 'page=1.5', 'size=0', 'size=201']) {
      const response = await fetch(API_BASE_URL + '/api/admin/agents?' + query)
      expect(response.status, query).toBe(400)
      expect(await response.json()).toMatchObject({ code: 'INVALID_INPUT' })
    }
    expect((await getAgents({ hostname: '  ' })).totalElements).toBe(3)
    const empty = await getAgents({ hostname: 'not-found', page: 0, size: 20 })
    expect(empty).toMatchObject({ content: [], totalElements: 0, totalPages: 0 })
  })
  it('domain client의 page, size로 PageResponse를 조회한다', async () => {
    const result = await getAgents({ page: 1, size: 1 })
    expect(result).toMatchObject({ page: 1, size: 1, totalElements: 3, totalPages: 3 })
    expect(result.content).toHaveLength(1)
    expect(result.content[0].agentId).toBe(mockAgents[1].agentId)
    expect(result).not.toHaveProperty('items')
    expect(result).not.toHaveProperty('nextCursor')
  })
})
