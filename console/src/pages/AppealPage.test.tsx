// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AppealPage } from './AppealPage'
import { getAppeal, submitAppeal, type AppealView } from '../lib/api/appeals'
import { ApiError } from '../lib/api/client'

vi.mock('../lib/api/appeals', () => ({ getAppeal: vi.fn(), submitAppeal: vi.fn() }))
const get = vi.mocked(getAppeal)
const submit = vi.mocked(submitAppeal)
const token = 'eyJ2IjoxfQ.c2lnbmF0dXJl'
const eventId = '0f8c2b9e-4d1a-4c7b-9a2e-5b3f6d7e8a91'
const before: AppealView = {
  eventId,
  blockedAt: '2026-11-26T10:12:03Z',
  filename: 'report.exe',
  verdict: { kind: 'SIMILARITY', threat: 'Mirai', summary: '알려진 악성코드와 매우 비슷한 파일입니다.' },
  appeal: null,
}
const submitted = { reason: '업무용 설치 파일입니다.', createdAt: '2026-11-26T10:13:00Z' }
const pending: AppealView = {
  ...before,
  appeal: { status: 'PENDING', ...submitted, decidedAt: null, decisionNote: null },
}
const approved: AppealView = {
  ...before,
  appeal: { status: 'APPROVED', ...submitted, decidedAt: '2026-11-26T10:14:00Z', decisionNote: null },
}
const rejected: AppealView = {
  ...before,
  appeal: { status: 'REJECTED', ...submitted, decidedAt: '2026-11-26T10:14:00Z', decisionNote: '업무 승인 대상이 아닙니다.' },
}

let root: Root
let container: HTMLDivElement
let visibility: DocumentVisibilityState
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  vi.useFakeTimers()
  get.mockReset(); submit.mockReset()
  visibility = 'visible'
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility })
  container = document.createElement('div'); document.body.append(container); root = createRoot(container)
})
afterEach(async () => {
  await act(async () => root.unmount())
  container.remove()
  vi.useRealTimers()
})
async function mount() {
  await act(async () => root.render(
    <MemoryRouter initialEntries={['/appeal/' + token]}>
      <Routes><Route path="/appeal/:token" element={<AppealPage />} /></Routes>
    </MemoryRouter>,
  ))
}
async function advance(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms) })
}
function button(text: string) {
  const node = [...container.querySelectorAll('button')].find((b) => b.textContent === text)
  if (!node) throw new Error('버튼 없음: ' + text)
  return node
}
async function setReason(value: string) {
  await act(async () => {
    const input = container.querySelector('textarea')!
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

it('토큰이_틀리면_만료_안내를_보여_준다', async () => {
  get.mockRejectedValue(new ApiError(404, {}))
  await mount()
  expect(container.textContent).toContain('링크가 만료되었거나 잘못되었습니다. 이벤트 ID를 보안 담당자에게 알려 주세요.')
  expect(container.querySelector('textarea')).toBeNull()
  await advance(9000)
  expect(get).toHaveBeenCalledTimes(1)
})

it('소명_전이면_차단_요약과_입력란을_보여_준다', async () => {
  get.mockResolvedValue(before)
  await mount()
  expect(get).toHaveBeenCalledWith(token, expect.anything())
  expect(container.textContent).toContain('Mirai')
  expect(container.textContent).toContain('알려진 악성코드와 매우 비슷한 파일입니다.')
  expect(container.textContent).toContain('report.exe')
  expect(container.querySelector('textarea')).not.toBeNull()
  expect(container.textContent).toContain('0 / 1000')
  expect(button('소명 제출')).toBeTruthy()
  // 소명 전에는 다시 조회하지 않는다.
  await advance(9000)
  expect(get).toHaveBeenCalledTimes(1)
})

it('근거가_없으면_근거를_불러오지_못했다고_알린다', async () => {
  get.mockResolvedValue({ ...before, verdict: null })
  await mount()
  expect(container.textContent).toContain('판정 근거를 불러오지 못했습니다')
  expect(button('소명 제출')).toBeTruthy()
})

it('파일명은_링크가_아니다', async () => {
  get.mockResolvedValue({ ...before, filename: 'https://evil.example/report.exe' })
  await mount()
  expect(container.textContent).toContain('https://evil.example/report.exe')
  for (const anchor of container.querySelectorAll('a')) {
    expect(anchor.textContent).not.toContain('report.exe')
  }
  for (const node of container.querySelectorAll('[href]')) {
    expect(node.getAttribute('href')).not.toContain('evil.example')
  }
})

it('공백만_입력하면_제출할_수_없다', async () => {
  get.mockResolvedValue(before)
  await mount()
  expect(button('소명 제출').disabled).toBe(true)
  await setReason('   \n  ')
  expect(button('소명 제출').disabled).toBe(true)
  expect(container.textContent).toContain('0 / 1000')
  await act(async () => button('소명 제출').click())
  expect(submit).not.toHaveBeenCalled()
})

it('1000자를_넘으면_제출할_수_없다', async () => {
  get.mockResolvedValue(before)
  await mount()
  // 코드 포인트 기준: 이모지 1000개는 1000자다.
  await setReason('😀'.repeat(1000))
  expect(container.textContent).toContain('1000 / 1000')
  expect(button('소명 제출').disabled).toBe(false)
  await setReason('가'.repeat(1001))
  expect(container.textContent).toContain('1001 / 1000')
  expect(button('소명 제출').disabled).toBe(true)
})

it('제출하면_검토_중으로_바뀐다', async () => {
  get.mockResolvedValue(before)
  submit.mockResolvedValue(pending)
  await mount()
  await setReason('  업무용 설치 파일입니다.  ')
  await act(async () => button('소명 제출').click())
  expect(submit).toHaveBeenCalledExactlyOnceWith(token, '업무용 설치 파일입니다.')
  expect(container.textContent).toContain('보안 담당자가 검토하고 있습니다.')
  expect(container.textContent).toContain('업무용 설치 파일입니다.')
  expect(container.querySelector('textarea')).toBeNull()
})

it('이미_제출된_소명이면_최신_상태를_다시_불러온다', async () => {
  get.mockResolvedValueOnce(before).mockResolvedValue(pending)
  submit.mockRejectedValue(new ApiError(409, {}))
  await mount()
  await setReason('다시 제출')
  await act(async () => button('소명 제출').click())
  expect(get).toHaveBeenCalledTimes(2)
  expect(container.textContent).toContain('보안 담당자가 검토하고 있습니다.')
})

it('제출이_실패하면_사유를_유지하고_다시_제출할_수_있다', async () => {
  get.mockResolvedValue(before)
  submit.mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValue(pending)
  await mount()
  await setReason('업무용 설치 파일입니다.')
  await act(async () => button('소명 제출').click())
  expect(container.querySelector('[role=alert]')?.textContent).toContain('다시 시도')
  expect(container.querySelector('textarea')?.value).toBe('업무용 설치 파일입니다.')
  await act(async () => button('소명 제출').click())
  expect(container.textContent).toContain('보안 담당자가 검토하고 있습니다.')
})

it('검토_중이면_3초마다_다시_조회하고_승인되면_멈춘다', async () => {
  get.mockResolvedValueOnce(pending).mockResolvedValueOnce(pending).mockResolvedValueOnce(approved)
  await mount()
  expect(get).toHaveBeenCalledTimes(1)
  expect(container.textContent).toContain('보안 담당자가 검토하고 있습니다.')
  await advance(2999)
  expect(get).toHaveBeenCalledTimes(1)
  await advance(1)
  expect(get).toHaveBeenCalledTimes(2)
  await advance(3000)
  expect(get).toHaveBeenCalledTimes(3)
  expect(container.textContent).toContain('승인되었습니다. 원래 페이지로 돌아가 다시 받아 주세요.')
  expect(container.textContent).not.toContain('보안 담당자가 검토하고 있습니다.')
  await advance(6000)
  expect(get).toHaveBeenCalledTimes(3)
  // 다시 받기 링크는 두지 않는다.
  expect(container.querySelector('a')).toBeNull()
})

it('조회가_일시적으로_실패해도_검토_중_화면을_유지하고_다시_시도한다', async () => {
  get.mockResolvedValueOnce(pending)
    .mockRejectedValueOnce(new ApiError(429, '<html><body>429 Too Many Requests</body></html>'))
    .mockRejectedValueOnce(new TypeError('Failed to fetch'))
    .mockRejectedValueOnce(new DOMException('signal timed out', 'TimeoutError'))
    .mockResolvedValueOnce(approved)
  await mount()
  for (const calls of [2, 3, 4]) {
    await advance(3000)
    expect(get).toHaveBeenCalledTimes(calls)
    expect(container.textContent).toContain('보안 담당자가 검토하고 있습니다.')
    expect(container.textContent).not.toContain('링크가 만료되었거나')
  }
  await advance(3000)
  expect(get).toHaveBeenCalledTimes(5)
  expect(container.textContent).toContain('승인되었습니다.')
})

it('탭이_숨겨지면_조회를_멈춘다', async () => {
  get.mockResolvedValue(pending)
  await mount()
  expect(get).toHaveBeenCalledTimes(1)
  visibility = 'hidden'
  await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
  await advance(12_000)
  expect(get).toHaveBeenCalledTimes(1)
  visibility = 'visible'
  await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
  expect(get).toHaveBeenCalledTimes(2)
  await advance(3000)
  expect(get).toHaveBeenCalledTimes(3)
})

it('거절되면_거절_사유를_보여_준다', async () => {
  get.mockResolvedValueOnce(pending).mockResolvedValue(rejected)
  await mount()
  await advance(3000)
  expect(container.textContent).toContain('거절되었습니다.')
  expect(container.textContent).toContain('업무 승인 대상이 아닙니다.')
  await advance(6000)
  expect(get).toHaveBeenCalledTimes(2)
})

it('화면을_떠나면_조회를_멈춘다', async () => {
  get.mockResolvedValue(pending)
  await mount()
  await act(async () => root.unmount())
  root = createRoot(container)
  await advance(9000)
  expect(get).toHaveBeenCalledTimes(1)
})

const EMOJI = /\p{Extended_Pictographic}/u

function progress() {
  return [...container.querySelectorAll('.appeal-progress li')].map((li) => ({
    label: li.textContent,
    state: li.getAttribute('data-state'),
    current: li.getAttribute('aria-current'),
  }))
}

it('이벤트_ID_앞_8자리를_보여_주고_전체는_title에_둔다', async () => {
  get.mockResolvedValue(before)
  await mount()
  const node = container.querySelector('.appeal-event-id code')!
  expect(node.textContent).toBe('0f8c2b9e')
  expect(node.getAttribute('title')).toBe(eventId)
  expect(container.textContent).toContain('문의: 보안 담당자에게 이벤트 ID를 알려 주세요.')
})

it('소명_전에는_진행_단계가_모두_비어_있다', async () => {
  get.mockResolvedValue(before)
  await mount()
  expect(progress()).toEqual([
    { label: '접수', state: 'todo', current: null },
    { label: '검토 중', state: 'todo', current: null },
    { label: '결정', state: 'todo', current: null },
  ])
})

it('검토_중이면_검토_중이_현재_단계다', async () => {
  get.mockResolvedValue(pending)
  await mount()
  expect(progress()).toEqual([
    { label: '접수', state: 'done', current: null },
    { label: '검토 중', state: 'done', current: 'step' },
    { label: '결정', state: 'todo', current: null },
  ])
})

it('승인되면_결정_칸이_승인으로_채워진다', async () => {
  get.mockResolvedValue(approved)
  await mount()
  expect(progress()[2]).toEqual({ label: '승인', state: 'approved', current: 'step' })
  expect(progress().slice(0, 2).map((step) => step.state)).toEqual(['done', 'done'])
})

it('거절되면_결정_칸이_거절로_채워진다', async () => {
  get.mockResolvedValue(rejected)
  await mount()
  expect(progress()[2]).toEqual({ label: '거절', state: 'rejected', current: 'step' })
})

it('어느_상태에서도_이모지를_쓰지_않는다', async () => {
  for (const view of [before, pending, approved, rejected]) {
    get.mockReset(); get.mockResolvedValue(view)
    await act(async () => root.unmount())
    root = createRoot(container)
    await mount()
    expect(container.textContent).not.toMatch(EMOJI)
  }
  get.mockReset(); get.mockRejectedValue(new ApiError(404, {}))
  await act(async () => root.unmount())
  root = createRoot(container)
  await mount()
  expect(container.textContent).not.toMatch(EMOJI)
})

it('상태가_바뀌어도_알림_영역은_그대로_두고_글자만_바꾼다', async () => {
  get.mockResolvedValueOnce(pending).mockResolvedValue(approved)
  await mount()
  const live = container.querySelector('[aria-live=polite][role=status]')!
  expect(live.textContent).toContain('보안 담당자가 검토하고 있습니다.')
  await advance(3000)
  expect(container.querySelector('[aria-live=polite][role=status]')).toBe(live)
  expect(live.textContent).toContain('승인되었습니다. 원래 페이지로 돌아가 다시 받아 주세요.')
})
