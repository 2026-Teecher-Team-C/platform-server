/// <reference types="node" />
import { afterEach, describe, expect, it, vi } from 'vitest'
import process from 'node:process'
import { Buffer } from 'node:buffer'
import { establishSession, endSession } from './session'
import { getAccessToken, getRefreshToken } from './tokenStore'
import { getMe } from '../api/auth'
import { getSample } from '../api/samples'
import { getDownloadEvent } from '../api/events'

// 명시적인 로컬 검증에서만 실행한다. 자격증명과 토큰은 로그나 파일에 출력하지 않는다.
const realEnabled = Boolean(process.env.AUTH_QA_USERNAME && process.env.AUTH_QA_PASSWORD && process.env.VITE_API_BASE_URL)
const storage = new Map<string, string>()
afterEach(() => vi.unstubAllGlobals())

describe.skipIf(!realEnabled)('실제 서버 인증 연동', () => {
  it('서버가 이미 회전한 응답이 로그아웃/새 로그인 뒤 도착해도 이전 세션과 새 refresh를 남기지 않는다', async () => {
    storage.clear()
    vi.stubGlobal('sessionStorage', { getItem: (key: string) => storage.get(key) ?? null, setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) })
    vi.stubGlobal('window', new EventTarget())
    const nativeFetch = globalThis.fetch
    const credentials = { username: process.env.AUTH_QA_USERNAME!, password: process.env.AUTH_QA_PASSWORD! }
    await establishSession(credentials)
    storage.set('teecher.console.accessToken', 'invalid-access-for-race')
    let finish!: () => void
    let lateRefresh: string | undefined
    let delayed = false
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      const response = await nativeFetch(input, init)
      // 실제 Redis 회전은 끝내고 응답 전달만 지연한다. 응답 본문과 비밀은 출력하지 않는다.
      if (String(input).endsWith('/auth/refresh') && response.ok && !delayed) {
        delayed = true
        lateRefresh = (await response.clone().json()).refreshToken
        await new Promise<void>((resolve) => { finish = resolve })
      }
      return response
    })
    const oldRequest = getMe()
    const rejected = expect(oldRequest).rejects.toMatchObject({ name: 'AbortError' })
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    const logout = endSession()
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    await establishSession(credentials)
    const newRefresh = getRefreshToken()
    finish()
    await rejected
    expect(await logout).toBe(true)
    expect(getRefreshToken() === newRefresh).toBe(true)
    expect((await getMe()).username === credentials.username).toBe(true)
    const revoked = await nativeFetch(process.env.VITE_API_BASE_URL + '/api/admin/auth/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: lateRefresh }) })
    expect(revoked.status).toBe(401)
    expect(await endSession()).toBe(true)
  })
  it('로그인, 실제 만료, 동시 갱신, 로그아웃과 폐기를 확인한다', async () => {
    vi.stubGlobal('sessionStorage', { getItem: (key: string) => storage.get(key) ?? null, setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) })
    vi.stubGlobal('window', new EventTarget())
    const nativeFetch = globalThis.fetch
    let refreshCount = 0
    vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/auth/refresh')) refreshCount += 1
      return nativeFetch(input, init)
    })
    const credentials = { username: process.env.AUTH_QA_USERNAME!, password: process.env.AUTH_QA_PASSWORD! }
    const initial = await establishSession(credentials)
    expect(initial.user.username === credentials.username).toBe(true)
    const oldRefresh = getRefreshToken()
    const payload = JSON.parse(Buffer.from(getAccessToken()!.split('.')[1], 'base64url').toString())
    // 서버의 허용 시계 오차 60초를 넘긴 뒤 짧은 수명의 QA access가 실제로 만료되는지 확인한다.
    await new Promise((resolve) => setTimeout(resolve, Math.max(0, payload.exp * 1000 - Date.now() + 62_000)))
    const users = await Promise.all([getMe(), getMe()])
    expect(users.every((user) => user.username === credentials.username)).toBe(true)
    expect(refreshCount).toBe(1)
    expect(getRefreshToken() !== oldRefresh).toBe(true)
    // 회전 전 토큰 재사용은 서버가 거부하며 현재 세션은 유지한다.
    const reused = await nativeFetch(process.env.VITE_API_BASE_URL + '/api/admin/auth/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: oldRefresh }) })
    expect(reused.status).toBe(401)
    if (process.env.AUTH_QA_SAMPLE && process.env.AUTH_QA_EVENT) {
      const event = await getDownloadEvent(process.env.AUTH_QA_EVENT)
      const sample = await getSample(process.env.AUTH_QA_SAMPLE)
      expect(sample.sha256).toBe(process.env.AUTH_QA_SAMPLE)
      expect(event.similarity?.matchedSampleSha256).toBe(sample.sha256)
    }
    const refresh = getRefreshToken()
    expect(await endSession()).toBe(true)
    expect(getAccessToken()).toBeNull()
    const revoked = await nativeFetch(process.env.VITE_API_BASE_URL + '/api/admin/auth/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: refresh }) })
    expect(revoked.status).toBe(401)
    const next = await establishSession(credentials)
    expect(Boolean(next.user)).toBe(true)
    storage.set('teecher.console.accessToken', 'invalid-fixture-access')
    storage.set('teecher.console.refreshToken', 'invalid-fixture-refresh')
    await expect(getMe()).rejects.toMatchObject({ status: 401 })
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  }, 120_000)
})
