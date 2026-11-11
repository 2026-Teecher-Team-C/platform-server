// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { router } from '../app/router'

const auth = vi.hoisted(() => ({ isAuthenticated: true }))
vi.mock('../app/useAuth', () => ({ useAuth: () => ({ isAuthenticated: auth.isAuthenticated, isInitializing: false, user: { role: 'VIEWER', username: 'reviewer' }, signOut: vi.fn() }) }))
vi.mock(import('../lib/auth/tokenStore'), async (original) => ({ ...await original(), getAccessToken: () => null }))
const sha = 'ab'.repeat(32)
const fixture = { sha256: sha, blacklist: { reason: 'Synthetic review only', severity: 'HIGH', source: 'MANUAL', isActive: true, registeredAt: '2026-10-23T00:00:00Z' }, fingerprint: { tlsh: 'T1' + 'A'.repeat(70), calculationStatus: 'CALCULATED', algorithmVersion: 'review-vector', provenance: 'ADMIN_IMPORT', provenanceDetail: 'Synthetic, not MalwareBazaar', calculatedAt: '2026-10-23T00:00:00Z' } }
let container: HTMLDivElement
let root: Root
beforeEach(() => {
  auth.isAuthenticated = true
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  container = document.createElement('div'); document.body.append(container); root = createRoot(container)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(fixture)))
})
afterEach(async () => { await act(async () => root.unmount()); container.remove(); vi.unstubAllGlobals() })
async function mount(path = '/samples/' + sha + '?returnTo=%2Fevents%2Freview') {
  await act(async () => { await router.navigate(path); root.render(<RouterProvider router={router} />) })
}
it('인증되지 않으면 샘플 API를 읽지 않고 로그인으로 이동한다', async () => {
  auth.isAuthenticated = false
  await mount()
  expect(router.state.location.pathname).toBe('/login')
  expect(fetch).not.toHaveBeenCalled()
})
it('계산 불가와 비활성을 표시하고 없는 출처 설명을 추정하지 않는다', async () => {
  vi.mocked(fetch).mockResolvedValueOnce(Response.json({ ...fixture,
    blacklist: { ...fixture.blacklist, isActive: false },
    fingerprint: { ...fixture.fingerprint, tlsh: null, calculationStatus: 'LOW_ENTROPY', provenanceDetail: null },
  }))
  await mount()
  expect(container.textContent).toContain('계산된 TLSH 없음')
  expect(container.textContent).toContain('LOW_ENTROPY')
  expect(container.textContent).toContain('비활성')
  expect(container.textContent).not.toContain('MalwareBazaar')
})
it.each(['bad', ' ' + sha, sha + ' ', sha + '\n', 'g'.repeat(64)])('잘못된 SHA 원문 %s은 API 조회 없이 안내한다', async (bad) => {
  await mount('/samples/' + encodeURIComponent(bad))
  expect(container.textContent).toContain('SHA-256 형식이 올바르지 않습니다')
  expect(fetch).not.toHaveBeenCalled()
})
it.each([400, 404, 403, 500])('%s 응답을 구분하고 일시 오류만 재시도한다', async (status) => {
  vi.mocked(fetch).mockResolvedValueOnce(Response.json({}, { status }))
  await mount()
  expect(container.querySelector('.event-detail-page [role="alert"]')).not.toBeNull()
  expect(container.textContent).toContain(status === 404 ? '현재 샘플 메타데이터를 찾을 수 없습니다' : status === 400 ? 'SHA-256 형식이 올바르지 않습니다' : status === 403 ? '조회 권한이 없습니다' : '샘플 상세를 불러오지 못했습니다')
  const retry = container.querySelector<HTMLButtonElement>('.event-detail-page button')
  if (status === 500) {
    expect(retry?.textContent).toBe('다시 시도')
    await act(async () => retry!.click())
    expect(container.textContent).toContain('Synthetic review only')
  } else expect(retry).toBeNull()
})
it.each(['성공', '실패'])('늦은 %s는 다른 SHA 화면의 로딩과 값을 덮지 않는다', async outcome => {
  let resolveOld!: (value: Response) => void
  let rejectOld!: (value: Error) => void
  let resolveNew!: (value: Response) => void
  vi.mocked(fetch).mockReturnValueOnce(new Promise((yes, no) => { resolveOld = yes; rejectOld = no }))
    .mockReturnValueOnce(new Promise(yes => { resolveNew = yes }))
  await mount()
  expect(container.querySelector('.event-detail-page [role="status"]')).not.toBeNull()
  await act(async () => { await router.navigate('/samples/' + 'cd'.repeat(32)) })
  await act(async () => { if (outcome === '성공') resolveOld(Response.json(fixture)); else rejectOld(new Error('late')) })
  expect(container.querySelector('.event-detail-page [role="status"]')).not.toBeNull()
  expect(container.textContent).not.toContain('Synthetic review only')
  await act(async () => resolveNew(Response.json({ ...fixture, sha256: 'cd'.repeat(32) })))
  expect(container.textContent).toContain('cd'.repeat(32))
  expect(container.textContent).not.toContain(sha)
})
it.each(['https://evil.example', '//evil.example', '/events/abc?redirect=evil', '/events/../blacklist', '/events/%2f%2fevil'])('이벤트 경로 이외의 returnTo %s는 무시한다', async returnTo => {
  await mount('/samples/' + sha + '?returnTo=' + encodeURIComponent(returnTo))
  expect(container.querySelector('.event-back-link')?.getAttribute('href')).toBe('/events')
})
it('없는 블랙리스트와 지문은 비활성/가짜 TLSH가 아니라 기록 없음으로 표시한다', async () => {
  vi.mocked(fetch).mockResolvedValueOnce(Response.json({ sha256: sha, blacklist: null, fingerprint: null }))
  await mount()
  expect(container.textContent).toContain('현재 블랙리스트 기록 없음')
  expect(container.textContent).toContain('지문 기록 없음')
  expect(container.textContent).not.toContain('비활성')
})
it('VIEWER가 보호된 샘플 경로에서 실제 계약의 현재 메타데이터를 읽고 원래 이벤트로 돌아간다', async () => {
  await mount()
  expect(container.textContent).toContain('샘플 상세')
  expect(container.textContent).toContain('Synthetic review only')
  expect(container.textContent).toContain(fixture.fingerprint.tlsh)
  expect(container.textContent).toContain('ADMIN_IMPORT')
  expect(container.textContent).toContain('review-vector')
  expect(container.textContent).toContain('현재 저장된 메타데이터')
  expect(container.querySelector('a[href="/events/review"]')).not.toBeNull()
  expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/api/admin/samples/' + sha), expect.anything())
  expect(container.querySelector('.event-detail-page button')).toBeNull()
  expect(container.querySelector('a[download]')).toBeNull()
})
