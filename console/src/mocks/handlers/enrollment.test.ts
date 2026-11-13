import { afterAll, beforeAll, expect, it, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { agentHandlers } from './agents'
import { API_BASE_URL } from '../../lib/api/client'
import { createEnrollmentToken } from '../../lib/api/agents'
const account = vi.hoisted(() => ({ adminId: 'qa-admin', role: 'ADMIN', isActive: true }))
// 보호된 계정 fixture 대신 권한 조회 경계만 대체해 실제 공통 인증 검사를 실행한다.
vi.mock('../auth', () => ({ getAuthenticatedMockAccount: (request: Request) => request.headers.has('Authorization') ? account : null }))
vi.mock('../data/auth', () => ({ hasAtLeastRole: (role: string, required: string) => required === 'USER' || role === required }))
vi.mock('../../lib/auth/tokenStore', async (original) => ({ ...await original<typeof import('../../lib/auth/tokenStore')>(), getAccessToken: () => 'fixture-only' }))
const server = setupServer(...agentHandlers)
beforeAll(() => server.listen())
afterAll(() => server.close())
const url = API_BASE_URL + '/api/admin/agents/enrollment-tokens'
const post = (body?: unknown) => fetch(url, { method: 'POST', headers: { Authorization: 'Bearer fixture-only', 'Content-Type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
it.each(['USER', 'ANALYST'])('%s는 서버와 같이 등록 토큰 발급을 403으로 거부한다', async (role) => {
  account.role = role
  try { expect((await post({})).status).toBe(403) }
  finally { account.role = 'ADMIN' }
})
it('로그인 없이 발급하면 401이다', async () => {
  expect((await fetch(url, { method: 'POST' })).status).toBe(401)
})
it.each([undefined, null, {}, { expiresInMinutes: null }])('ADMIN이 생략한 유효 시간은 기본 60분이다 (%j)', async (body) => {
  const before = Date.now()
  const response = await post(body)
  expect(response.status).toBe(201)
  const data = await response.json()
  expect(Object.keys(data).sort()).toEqual(['expiresAt', 'token'])
  expect(typeof data.token).toBe('string')
  expect(Date.parse(data.expiresAt)).toBeGreaterThanOrEqual(before + 60 * 60_000)
  expect(Date.parse(data.expiresAt)).toBeLessThanOrEqual(Date.now() + 60 * 60_000)
})
it.each([0, -1, 1441, 1.5, '60'])('범위나 타입이 잘못되면 400 INVALID_INPUT (%j)', async (expiresInMinutes) => {
  const response = await post({ expiresInMinutes })
  expect(response.status).toBe(400)
  expect(await response.json()).toMatchObject({ code: 'INVALID_INPUT' })
})
it.each([1, 1440])('domain client는 허용 경계의 요청과 응답을 그대로 사용한다 (%j)', async (expiresInMinutes) => {
  const before = Date.now()
  const response = await createEnrollmentToken({ expiresInMinutes })
  expect(typeof response.token).toBe('string')
  expect(Date.parse(response.expiresAt)).toBeGreaterThanOrEqual(before + expiresInMinutes * 60_000)
})

it('일반 HTTP 개발 환경에서도 보안 난수로 등록 토큰을 발급한다', async () => {
  vi.stubGlobal('crypto', { getRandomValues: crypto.getRandomValues.bind(crypto) })
  try {
    expect((await post({})).status).toBe(201)
  } finally { vi.unstubAllGlobals() }
})
