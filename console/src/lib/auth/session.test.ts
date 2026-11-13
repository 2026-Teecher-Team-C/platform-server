import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { establishSession, endSession } from './session'
import { apiRequest } from '../api/client'
import { clearAccessToken, getAccessToken, getRefreshToken, getSessionVersion, setAuthTokens } from './tokenStore'

const storage = new Map<string, string>()
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
beforeEach(() => {
  storage.clear()
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => storage.get(key) ?? null, setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) })
  vi.stubGlobal('window', new EventTarget())
})
afterEach(() => vi.unstubAllGlobals())

describe('로그인 세션', () => {
  // 첫 갱신이 refresh를 소비한 뒤 응답만 늦어지는 서버 경합을 재현한다.
  it.each([
    ['http', false], ['network', false], ['success', false],
    ['http', true], ['network', true], ['success', true],
  ] as const)('진행 중 갱신의 늦은 토큰 폐기 결과를 로그아웃에 반영한다: %s, 새 로그인=%s', async (cleanup, newLogin) => {
    setAuthTokens({ accessToken: 'expired-access', refreshToken: 'consumed-refresh' })
    let finishRefresh!: (response: Response) => void
    let finishCleanup!: (response: Response) => void
    let failCleanup!: (reason: Error) => void
    let refreshCalls = 0
    const fetcher = vi.fn((url: string, options: RequestInit) => {
      if (url.endsWith('/login')) return Promise.resolve(json({ accessToken: 'other-access', refreshToken: 'other-refresh', tokenType: 'Bearer' }))
      if (url.endsWith('/me')) return Promise.resolve(json({ username: 'other-admin' }))
      if (url.endsWith('/refresh')) {
        refreshCalls += 1
        if (refreshCalls === 1) return new Promise<Response>((resolve) => { finishRefresh = resolve })
        return Promise.resolve(json({}, 401))
      }
      if (url.endsWith('/logout') && new Headers(options.headers).get('Authorization') === 'Bearer late-access') {
        return new Promise<Response>((resolve, reject) => { finishCleanup = resolve; failCleanup = reject })
      }
      return Promise.resolve(json({}, 401))
    })
    vi.stubGlobal('fetch', fetcher)
    const oldRequest = apiRequest('/api/admin/protected')
    const rejected = expect(oldRequest).rejects.toMatchObject({ name: 'AbortError' })
    await vi.waitFor(() => expect(finishRefresh).toBeTypeOf('function'))
    let settled = false
    const pendingLogout = endSession().then((result) => { settled = true; return result })
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    await vi.waitFor(() => expect(refreshCalls).toBe(2))
    finishRefresh(json({ accessToken: 'late-access', refreshToken: 'late-refresh' }))
    await vi.waitFor(() => expect(finishCleanup).toBeTypeOf('function'))
    const settledBeforeCleanup = settled
    const logoutVersion = getSessionVersion()
    if (newLogin) await establishSession({ username: 'other-admin', password: 'fixture-only' })
    if (cleanup === 'network') failCleanup(new TypeError('Network unavailable'))
    else finishCleanup(cleanup === 'success' ? new Response(null, { status: 204 }) : json({}, 503))
    await rejected
    expect(await pendingLogout).toBe(cleanup === 'success')
    expect(settledBeforeCleanup).toBe(false)
    expect(getAccessToken()).toBe(newLogin ? 'other-access' : null)
    expect(getRefreshToken()).toBe(newLogin ? 'other-refresh' : null)
    // AuthProvider의 경고 조건은 현재 로그아웃 세대에서만 참이어야 한다.
    expect(getSessionVersion() === logoutVersion).toBe(!newLogin)
    const cleanupCall = fetcher.mock.calls.find(([, options]) => new Headers(options.headers).get('Authorization') === 'Bearer late-access')
    expect(JSON.parse(cleanupCall![1].body as string)).toEqual({ refreshToken: 'late-refresh' })
  })

  it('갱신 뒤 토큰 폐기가 실패하면 성공으로 보고하지 않는다', async () => {
    setAuthTokens({ accessToken: 'expired-access', refreshToken: 'refresh' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({}, 401)).mockResolvedValueOnce(json({ accessToken: 'new-access', refreshToken: 'new-refresh' })).mockResolvedValueOnce(json({}, 401)))
    expect(await endSession()).toBe(false)
  })

  it('토큰 쌍을 저장하고 현재 관리자 정보를 확인한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer' })).mockResolvedValueOnce(json({ username: 'qa-admin' })))
    const result = await establishSession({ username: 'qa-admin', password: 'fixture-only' })
    expect(result.user.username).toBe('qa-admin')
    expect(getAccessToken()).toBe('access')
    expect(getRefreshToken()).toBe('refresh')
  })

  it('로그아웃은 네트워크 응답을 기다리기 전에 로컬 토큰을 비운다', async () => {
    setAuthTokens({ accessToken: 'access', refreshToken: 'refresh' })
    let finish!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise<Response>((resolve) => { finish = resolve })))
    const request = endSession()
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    finish(new Response(null, { status: 204 }))
    expect(await request).toBe(true)
  })

  it('로그아웃 뒤 도착한 로그인 응답은 세션을 되살리지 않는다', async () => {
    let finish!: (value: Response) => void
    const fetcher = vi.fn().mockReturnValueOnce(new Promise<Response>((resolve) => { finish = resolve })).mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetcher)
    const request = establishSession({ username: 'qa-admin', password: 'fixture-only' })
    const assertion = expect(request).rejects.toMatchObject({ name: 'AbortError' })
    clearAccessToken()
    finish(json({ accessToken: 'late-access', refreshToken: 'late-refresh', tokenType: 'Bearer' }))
    await assertion
    expect(getAccessToken()).toBeNull()
  })
})
