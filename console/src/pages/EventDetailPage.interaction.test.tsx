// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Link, MemoryRouter, Routes, Route } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { EventDetailPage } from './EventDetailPage'
import { getDownloadEvent } from '../lib/api/events'
import { mockDownloadEvents } from '../mocks/data/events'
import { ApiError } from '../lib/api/client'

vi.mock('../lib/api/events', () => ({ getDownloadEvent: vi.fn() }))
let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  vi.resetAllMocks()
  container = document.createElement('div'); document.body.append(container); root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })
async function mount() {
  await act(async () => root.render(<MemoryRouter initialEntries={['/events/warn']}><Link to="/events/next">다른 이벤트</Link><Routes><Route path="/events/:eventId" element={<EventDetailPage />} /></Routes></MemoryRouter>))
}
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
