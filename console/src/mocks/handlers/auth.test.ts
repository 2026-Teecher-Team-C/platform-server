import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { authHandlers } from './auth'
import { API_BASE_URL } from '../../lib/api/client'

// 계정 파일의 실제 테스트 비밀번호를 읽지 않고 인증 프로토콜만 시험한다.
const account = vi.hoisted(() => ({ adminId: 'qa-admin', username: 'qa-user', role: 'ADMIN', isActive: true, lastLoginAt: null as string | null }))
vi.mock('../data/auth', () => ({
  mockAdminAccounts: [account],
  findMockAccountByCredentials: () => account,
  findMockAccountByAccessToken: () => null,
  toCurrentAdmin: (value: unknown) => value,
}))
const server = setupServer(...authHandlers)
beforeAll(() => server.listen())
afterAll(() => server.close())
afterEach(() => vi.unstubAllGlobals())
const post = (path: string, body: unknown, token?: string) => fetch(API_BASE_URL + '/api/admin/auth/' + path, {
  method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: JSON.stringify(body),
})

describe('mock 인증 API', () => {
  it('모듈이 다시 로드되어도 같은 탭의 mock 세션은 유지된다', async () => {
    const stored = new Map<string, string>()
    vi.stubGlobal('sessionStorage', { getItem: (key: string) => stored.get(key) ?? null, setItem: (key: string, value: string) => stored.set(key, value) })
    const { issueMockSession } = await import('../sessions')
    const { mockAdminAccounts } = await import('../data/auth')
    const tokens = issueMockSession(mockAdminAccounts[0])
    vi.resetModules()
    const reloaded = await import('../sessions')
    expect(reloaded.findMockSession(tokens.accessToken)?.adminId).toBe(account.adminId)
    expect(Boolean(reloaded.rotateMockSession(tokens.refreshToken))).toBe(true)
  })

  it('일반 HTTP 개발 주소에서도 보안 난수로 토큰을 만든다', async () => {
    vi.stubGlobal('crypto', { getRandomValues: crypto.getRandomValues.bind(crypto) })
    expect((await post('login', { username: 'qa-user', password: 'fixture-only' })).status).toBe(200)
  })

  it('refresh를 회전하고 로그아웃한 refresh의 재사용을 거부한다', async () => {
    const first = await (await post('login', { username: 'qa-user', password: 'fixture-only' })).json()
    const response = await post('refresh', { refreshToken: first.refreshToken })
    expect(response.status).toBe(200)
    const second = await response.json()
    expect(second.refreshToken).not.toBe(first.refreshToken)
    expect((await post('refresh', { refreshToken: first.refreshToken })).status).toBe(401)
    expect((await post('logout', { refreshToken: second.refreshToken }, second.accessToken)).status).toBe(204)
    expect((await post('refresh', { refreshToken: second.refreshToken })).status).toBe(401)
  })
})
