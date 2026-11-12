import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './client'
import { clearAccessToken } from '../auth/tokenStore'
import { getSample } from './samples'
import { getDownloadEvent } from './events'

const storage = new Map<string, string>()
const accessKey = 'teecher.console.accessToken'
const refreshKey = 'teecher.console.refreshToken'
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  storage.clear()
  vi.stubGlobal('sessionStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
  })
  vi.stubGlobal('window', new EventTarget())
  storage.set(accessKey, 'expired-access')
  storage.set(refreshKey, 'valid-refresh')
})
afterEach(() => vi.unstubAllGlobals())

describe('인증 요청 갱신', () => {
  it('본문을 읽는 중 세션이 바뀌면 이전 계정의 응답을 반환하지 않는다', async () => {
    let finish!: (value: unknown) => void
    const response = json({})
    vi.spyOn(response, 'json').mockImplementation(() => new Promise((resolve) => { finish = resolve }))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    const request = apiRequest('/api/admin/protected')
    const assertion = expect(request).rejects.toMatchObject({ name: 'AbortError' })
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    clearAccessToken()
    finish({ privateData: 'previous-session' })
    await assertion
  })

  it('동시 401은 갱신 요청 하나를 공유한다', async () => {
    let finish!: (response: Response) => void
    const fetcher = vi.fn((url: string, options: RequestInit) => {
      if (url.endsWith('/refresh')) return new Promise<Response>((resolve) => { finish = resolve })
      return Promise.resolve(new Headers(options.headers).get('Authorization') === 'Bearer new-access' ? json({ ok: true }) : json({}, 401))
    })
    vi.stubGlobal('fetch', fetcher)
    // 현재 TLSH 도메인 API도 같은 갱신을 공유하며 GET 본문/JSON 헤더를 만들지 않는다.
    const first = getSample('ab'.repeat(32))
    const second = getDownloadEvent('event-fixture')
    await vi.waitFor(() => expect(fetcher.mock.calls.filter(([url]) => url.endsWith('/refresh'))).toHaveLength(1))
    finish(json({ accessToken: 'new-access', refreshToken: 'new-refresh' }))
    expect(await Promise.all([first, second])).toEqual([{ ok: true }, { ok: true }])
    for (const [, options] of fetcher.mock.calls.filter(([url]) => !url.endsWith('/refresh'))) {
      expect(options.body).toBeUndefined()
      expect(new Headers(options.headers).has('Content-Type')).toBe(false)
    }
    expect(fetcher.mock.calls.filter(([url]) => url.endsWith('/refresh'))).toHaveLength(1)
  })

  it('갱신 후 재시도도 401이면 반복하지 않고 세션을 비운다', async () => {
    const notify = vi.fn()
    window.addEventListener('teecher:auth-unauthorized', notify)
    const fetcher = vi.fn().mockResolvedValueOnce(json({}, 401)).mockResolvedValueOnce(json({ accessToken: 'new-access', refreshToken: 'new-refresh' })).mockResolvedValueOnce(json({}, 401))
    vi.stubGlobal('fetch', fetcher)
    await expect(apiRequest('/api/admin/protected')).rejects.toMatchObject({ status: 401 })
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(storage.has(accessKey)).toBe(false)
    expect(storage.has(refreshKey)).toBe(false)
    expect(notify).toHaveBeenCalledTimes(1)
  })

  it('로그아웃 뒤 완료된 갱신은 저장하지 않고 발급된 refresh도 폐기한다', async () => {
    let finish!: (response: Response) => void
    const fetcher = vi.fn((url: string) => {
      if (url.endsWith('/refresh')) return new Promise<Response>((resolve) => { finish = resolve })
      return Promise.resolve(url.endsWith('/logout') ? new Response(null, { status: 204 }) : json({}, 401))
    })
    vi.stubGlobal('fetch', fetcher)
    const request = apiRequest('/api/admin/protected')
    const assertion = expect(request).rejects.toMatchObject({ name: 'AbortError' })
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    clearAccessToken()
    finish(json({ accessToken: 'late-access', refreshToken: 'late-refresh' }))
    await assertion
    expect(storage.has(accessKey)).toBe(false)
    expect(fetcher.mock.calls.some(([url]) => url.endsWith('/logout'))).toBe(true)
  })

  it('403 응답은 갱신하거나 현재 세션을 삭제하지 않는다', async () => {
    const fetcher = vi.fn().mockResolvedValue(json({}, 403))
    vi.stubGlobal('fetch', fetcher)
    await expect(apiRequest('/api/admin/protected')).rejects.toMatchObject({ status: 403 })
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(storage.get(refreshKey)).toBe('valid-refresh')
  })

  it('401이면 토큰 쌍을 갱신한 뒤 원래 요청을 한 번 재시도한다', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(json({ code: 'UNAUTHORIZED' }, 401))
      .mockResolvedValueOnce(json({ accessToken: 'new-access', refreshToken: 'new-refresh', tokenType: 'Bearer' }))
      .mockResolvedValueOnce(json({ username: 'qa-user' }))
    vi.stubGlobal('fetch', fetcher)
    expect(await apiRequest('/api/admin/auth/me')).toEqual({ username: 'qa-user' })
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(fetcher.mock.calls[1][0]).toContain('/auth/refresh')
    expect(storage.get(accessKey)).toBe('new-access')
    expect(storage.get(refreshKey)).toBe('new-refresh')
    expect(new Headers(fetcher.mock.calls[2][1].headers).get('Authorization')).toBe('Bearer new-access')
  })
})

// YARA 업로드도 자동 갱신을 거치므로 같은 FormData와 브라우저 multipart 경계를 보존한다.
it('FormData 업로드는 refresh 전후 모두 JSON 헤더 없이 같은 본문을 재시도한다', async () => {
  const body = new FormData()
  body.append('file', new Blob(['rule qa { condition: true }']), 'qa.yar')
  const fetcher = vi.fn().mockResolvedValueOnce(json({}, 401))
    .mockResolvedValueOnce(json({ accessToken: 'new-access', refreshToken: 'new-refresh' }))
    .mockResolvedValueOnce(json({ ok: true }))
  vi.stubGlobal('fetch', fetcher)
  await apiRequest('/api/admin/yara-rules', { method: 'POST', body })
  for (const index of [0, 2]) {
    const options = fetcher.mock.calls[index][1]
    expect(options.body === body).toBe(true)
    expect(new Headers(options.headers).has('Content-Type')).toBe(false)
  }
})
it('문자열 본문은 JSON Content-Type을 유지한다', async () => {
  const fetcher = vi.fn().mockResolvedValue(json({ ok: true }))
  vi.stubGlobal('fetch', fetcher)
  await apiRequest('/api/admin/qa', { method: 'POST', body: '{}' })
  expect(new Headers(fetcher.mock.calls[0][1].headers).get('Content-Type')).toBe('application/json')
})

// 일시적인 갱신 장애는 자격 증명이 틀렸다는 뜻이 아니다.
it.each([500, 502, 503])('refresh %i는 토큰과 세션을 보존하고 반복하지 않는다', async (status) => {
  const notify = vi.fn()
  window.addEventListener('teecher:auth-unauthorized', notify)
  const fetcher = vi.fn().mockResolvedValueOnce(json({}, 401)).mockResolvedValueOnce(json({}, status))
  vi.stubGlobal('fetch', fetcher)
  await expect(apiRequest('/protected')).rejects.toMatchObject({ status })
  expect(storage.get(accessKey)).toBe('expired-access')
  expect(storage.get(refreshKey)).toBe('valid-refresh')
  expect(notify).not.toHaveBeenCalled()
  expect(fetcher).toHaveBeenCalledTimes(2)
})
it('refresh 네트워크 오류는 세션을 보존한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({}, 401)).mockRejectedValueOnce(new TypeError('offline')))
  await expect(apiRequest('/protected')).rejects.toThrow('offline')
  expect(storage.get(refreshKey)).toBe('valid-refresh')
})
it.each([401, 403])('refresh %i는 자격 거절로 세션을 삭제한다', async (status) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({}, 401)).mockResolvedValueOnce(json({}, status)))
  await expect(apiRequest('/protected')).rejects.toMatchObject({ status })
  expect(storage.has(refreshKey)).toBe(false)
})

it('실패 본문을 읽는 중 로그아웃하면 이전 세션의 403 오류도 숨긴다', async () => {
  let finish!: (value: unknown) => void
  const response = json({}, 403)
  vi.spyOn(response, 'json').mockImplementation(() => new Promise((resolve) => { finish = resolve }))
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
  const request = apiRequest('/protected')
  const assertion = expect(request).rejects.toMatchObject({ name: 'AbortError' })
  await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
  clearAccessToken()
  finish({ privateData: 'old-session' })
  await assertion
})
