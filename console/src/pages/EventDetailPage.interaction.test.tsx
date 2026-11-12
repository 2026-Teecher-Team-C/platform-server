// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Link, MemoryRouter, Routes, Route } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { EventDetailPage } from './EventDetailPage'
import { getDownloadEvent } from '../lib/api/events'
import { mockDownloadEvents } from '../mocks/data/events'
import { ApiError } from '../lib/api/client'
import { AuthContext } from '../app/auth-context'
import type { AdminRole } from '../lib/api/auth'
import { RoleGuard } from '../components/auth/RoleGuard'

vi.mock('../lib/api/events', () => ({ getDownloadEvent: vi.fn() }))
let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  vi.resetAllMocks()
  container = document.createElement('div'); document.body.append(container); root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })
async function mount(role: AdminRole = 'ADMIN') {
  await act(async () => root.render(<AuthContext.Provider value={{ user: { adminId: 'reviewer', username: 'reviewer', role, isActive: true, lastLoginAt: null }, isInitializing: false, authNotice: '', isAuthenticated: true, signIn: vi.fn(), signOut: vi.fn() }}><MemoryRouter initialEntries={['/events/warn']}><Link to="/events/next">다른 이벤트</Link><Routes><Route element={<RoleGuard />}><Route path="/events/:eventId" element={<EventDetailPage />} /></Route></Routes></MemoryRouter></AuthContext.Provider>))
}
it.each(['ADMIN', 'ANALYST'] as const)('%s 실제 이벤트 부모에서 권한에 맞게 SHA를 표시하고 당시 근거를 보존한다', async role => {
  const sha = 'ab'.repeat(32)
  const assessedAt = '2026-10-23T00:00:00Z'
  vi.mocked(getDownloadEvent).mockResolvedValue({ ...mockDownloadEvents[0], decision: 'RELEASED', decisionSource: 'ENGINE',
    similarity: { classification: 'WARN', matchedSampleSha256: sha, distance: 45, assessedAt } })
  await mount(role)
  const evidence = container.querySelector('.event-similarity')!
  expect(evidence.textContent).toContain(sha)
  expect(evidence.querySelector('dd')?.textContent).toBe('45')
  expect(evidence.textContent).toContain(new Date(assessedAt).toLocaleString('ko-KR'))
  expect(evidence.querySelector('details')?.open).toBe(false)
  expect(evidence.querySelector('[role="img"]')?.getAttribute('aria-label')).toBe('유사도 경고')
  expect(container.textContent).toContain('RELEASED')
  expect(container.textContent).toContain('ENGINE')
  expect(container.querySelector('.event-back-link')?.getAttribute('href')).toBe('/events')
  if (role === 'ADMIN') {
    expect(evidence.querySelector('a')?.getAttribute('href')).toBe('/samples/' + sha + '?returnTo=%2Fevents%2Fwarn')
    expect(evidence.textContent).not.toContain('샘플 상세는 ADMIN만 조회할 수 있습니다.')
  } else {
    expect(evidence.querySelector('a')).toBeNull()
    expect(evidence.textContent).toContain('샘플 상세는 ADMIN만 조회할 수 있습니다.')
  }
})
it.each(['ADMIN', 'ANALYST'] as const)('%s 후보가 없는 과거 평가에는 가짜 SHA, 링크, 권한 안내를 만들지 않는다', async role => {
  vi.mocked(getDownloadEvent).mockResolvedValue({ ...mockDownloadEvents[0], similarity: { classification: 'NONE', matchedSampleSha256: null, distance: null, assessedAt: '2026-10-23T00:00:00Z' } })
  await mount(role)
  const evidence = container.querySelector('.event-similarity')!
  expect(evidence.textContent).toContain('비교 가능한 기준 샘플 없음')
  expect(evidence.textContent).not.toContain('최근접 기준 샘플 SHA-256')
  expect(evidence.textContent).not.toContain('ADMIN만')
  expect(evidence.querySelector('a')).toBeNull()
})
it('USER는 기존 가드에서 이벤트 상세를 읽을 수 없다', async () => {
  await mount('USER')
  expect(container.textContent).toContain('접근 권한')
  expect(getDownloadEvent).not.toHaveBeenCalled()
})
it('경고 근거와 RELEASED를 함께 표시하고 백분율로 바꾸지 않는다', async () => {
  vi.mocked(getDownloadEvent).mockResolvedValue({ ...mockDownloadEvents[0], decision: 'RELEASED', decisionSource: 'ENGINE',
    similarity: { classification: 'WARN', matchedSampleSha256: 'ab'.repeat(32), distance: 45, assessedAt: '2026-10-23T00:00:00Z' } })
  await mount()
  expect(container.textContent).toContain('TLSH 유사도')
  expect(container.textContent).toContain('유사도 경고')
  expect(container.textContent).toContain('경고만으로 파일이 차단된 것은 아닙니다')
  expect(container.textContent).toContain('RELEASED')
  expect(container.textContent).toContain('ab'.repeat(32))
  expect(container.textContent).not.toContain('45%')
  expect(container.querySelector('a[href*="samples"]')?.getAttribute('href')).toBe('/samples/' + 'ab'.repeat(32) + '?returnTo=%2Fevents%2Fwarn')
})
it('조회 오류 후 재시도로 현재 근거를 불러온다', async () => {
  vi.mocked(getDownloadEvent).mockRejectedValueOnce(new Error('injected')).mockResolvedValueOnce(mockDownloadEvents[0])
  await mount()
  expect(container.querySelector('[role="alert"]')).not.toBeNull()
  await act(async () => container.querySelector('button')!.click())
  expect(container.textContent).toContain('기록된 유사도 평가 없음')
  expect(getDownloadEvent).toHaveBeenCalledTimes(2)
})
it('404에서는 근거를 만들거나 재시도 버튼을 보여주지 않는다', async () => {
  vi.mocked(getDownloadEvent).mockRejectedValue(new ApiError(404, {}))
  await mount()
  expect(container.textContent).toContain('이벤트를 찾을 수 없습니다')
  expect(container.textContent).not.toContain('TLSH 유사도')
  expect(container.querySelector('button')).toBeNull()
})
it.each(['성공', '실패'])('이전 이벤트의 늦은 %s가 현재 로딩과 근거를 덮지 않는다', async (outcome) => {
  let resolveOld!: (value: typeof mockDownloadEvents[0]) => void
  let rejectOld!: (error: Error) => void
  let resolveNew!: (value: typeof mockDownloadEvents[0]) => void
  vi.mocked(getDownloadEvent).mockReturnValueOnce(new Promise((yes, no) => { resolveOld = yes; rejectOld = no }))
    .mockReturnValueOnce(new Promise(yes => { resolveNew = yes }))
  await mount()
  expect(container.querySelector('[role="status"]')).not.toBeNull()
  await act(async () => container.querySelector<HTMLAnchorElement>('a')!.click())
  await act(async () => { if (outcome === '성공') resolveOld(mockDownloadEvents[0]); else rejectOld(new Error('late')) })
  expect(container.querySelector('[role="status"]')).not.toBeNull()
  expect(container.textContent).not.toContain('TLSH 유사도')
  await act(async () => resolveNew({ ...mockDownloadEvents[0], eventId: 'next', filename: 'new-current.txt' }))
  expect(container.textContent).toContain('new-current.txt')
  expect(container.textContent).toContain('기록된 유사도 평가 없음')
})
