import { afterEach, expect, it, vi } from 'vitest'
import { clearAccessToken, getAccessToken, setAccessToken } from './tokenStore'

afterEach(() => {
  vi.unstubAllGlobals()
})

it('sessionStorage가 없는 환경에서는 토큰이 없는 것으로 보고 저장·삭제는 무시한다', () => {
  vi.stubGlobal('sessionStorage', undefined)

  expect(getAccessToken()).toBeNull()
  expect(() => setAccessToken('token')).not.toThrow()
  expect(() => clearAccessToken()).not.toThrow()
})

it('sessionStorage가 있으면 토큰을 저장하고 읽고 지운다', () => {
  const store = new Map<string, string>()
  vi.stubGlobal('sessionStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => store.set(key, value),
    removeItem: (key: string) => store.delete(key),
  })

  setAccessToken('token')
  expect(getAccessToken()).toBe('token')
  clearAccessToken()
  expect(getAccessToken()).toBeNull()
})
