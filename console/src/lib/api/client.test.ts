import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './client'

function stubFetch() {
  const fetchMock = vi.fn(async () =>
    new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('apiRequest', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('세션 쿠키가 실리도록 credentials: include로 보낸다', async () => {
    const fetchMock = stubFetch()

    await apiRequest('/api/admin/auth/me')

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(init.credentials).toBe('include')
  })

  it('Authorization 헤더를 붙이지 않는다', async () => {
    const fetchMock = stubFetch()

    await apiRequest('/api/admin/auth/login', { method: 'POST', body: '{}' })

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    const headers = init.headers as Record<string, string>
    expect(Object.keys(headers).map((name) => name.toLowerCase())).not.toContain('authorization')
    expect(headers['Content-Type']).toBe('application/json')
  })
})
