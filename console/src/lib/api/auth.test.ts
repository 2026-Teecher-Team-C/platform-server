import { afterEach, describe, expect, it, vi } from 'vitest'
import { login, logout } from './auth'

afterEach(() => vi.unstubAllGlobals())

describe('인증 API', () => {
  it('로그인 실패는 기존 토큰을 갱신하지 않고 401로 반환한다', async () => {
    vi.stubGlobal('sessionStorage', { getItem: () => 'existing-session', removeItem: () => {} })
    vi.stubGlobal('window', new EventTarget())
    const fetcher = vi.fn().mockResolvedValue(new Response('{}', { status: 401, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetcher)
    await expect(login({ username: 'qa-user', password: 'qa-fixture' })).rejects.toMatchObject({ status: 401 })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('로그아웃은 요청 당시의 토큰으로 서버 폐기를 요청하고 204를 처리한다', async () => {
    vi.stubGlobal('sessionStorage', { getItem: () => 'different-session' })
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetcher)
    await expect(logout({ accessToken: 'old-access', refreshToken: 'old-refresh' })).resolves.toBeUndefined()
    const [url, options] = fetcher.mock.calls[0]
    expect(url).toContain('/auth/logout')
    expect(new Headers(options.headers).get('Authorization')).toBe('Bearer old-access')
    expect(JSON.parse(options.body)).toEqual({ refreshToken: 'old-refresh' })
  })
})
