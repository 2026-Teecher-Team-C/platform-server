// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { QuarantineDetailPage } from './QuarantineDetailPage'
import { getQuarantineFile, restoreQuarantineFile } from '../lib/api/quarantine'
import { ApiError } from '../lib/api/client'

vi.mock('../lib/api/quarantine', () => ({ getQuarantineFile: vi.fn(), restoreQuarantineFile: vi.fn() }))
const get = vi.mocked(getQuarantineFile)
const restore = vi.mocked(restoreQuarantineFile)
const id = '10000000-0000-4000-8000-000000000001'
const item: Awaited<ReturnType<typeof getQuarantineFile>> = {
  quarantineId: id, eventId: '20000000-0000-4000-8000-000000000001', agentId: '30000000-0000-4000-8000-000000000001',
  hostname: 'C2-PC', sha256: 'a'.repeat(64), originalFilename: 'sample.txt', fileSize: 17,
  status: 'QUARANTINED', quarantinedAt: '2026-10-28T00:00:00Z', restoredAt: null,
}
const restored = { quarantineId: id, sha256: item.sha256, status: 'RESTORED' as const, whitelisted: true as const, restoredAt: '2026-10-29T00:00:00Z' }
let root: Root
let container: HTMLDivElement
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  get.mockReset(); restore.mockReset()
  get.mockResolvedValue(item)
  restore.mockResolvedValue(restored)
  container = document.createElement('div'); document.body.append(container); root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })
async function mount() {
  await act(async () => root.render(<MemoryRouter initialEntries={['/quarantine/' + id]}><Link id="test-next-file" to="/quarantine/other-file">다른 파일</Link><Routes><Route path="/quarantine/:quarantineId" element={<QuarantineDetailPage />} /></Routes></MemoryRouter>))
}
function button(text: string) {
  const node = [...container.querySelectorAll('button')].find((b) => b.textContent === text)
  if (!node) throw new Error('버튼 없음: ' + text)
  return node
}
async function click(text: string) { await act(async () => button(text).click()) }
async function setReason(value: string) {
  await act(async () => {
    const input = container.querySelector('textarea')!
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

it.each([
  [404, '찾을 수 없습니다'],
  [409, '복원할 수 없는 상태'],
  [400, '입력값'],
  [401, '로그인'],
  [403, '권한'],
] as const)('복원 %i 오류는 구분해서 안내하고 사유를 유지하며 상태 재확인을 요구한다', async (status, message) => {
  restore.mockRejectedValueOnce(new ApiError(status, {}))
  await mount()
  await click('오탐으로 복원')
  await setReason('다시 확인할 사유')
  await click('복원 확인')
  expect(container.querySelector('[role=alert]')?.textContent).toContain(message)
  expect(container.querySelector('textarea')?.value).toBe('다시 확인할 사유')
  expect(button('복원 확인').disabled).toBe(true)
  expect(button('상태 새로고침').disabled).toBe(false)
})

it('응답을 확인하지 못한 뒤 확인창을 다시 열어도 재조회 전에는 복원을 다시 보내지 않는다', async () => {
  restore.mockRejectedValueOnce(new TypeError('Failed to fetch'))
  await mount()
  await click('오탐으로 복원')
  await setReason('서버 상태 확인 필요')
  await click('복원 확인')
  expect(container.querySelector('textarea')?.value).toBe('서버 상태 확인 필요')
  await click('취소')
  await click('오탐으로 복원')
  expect(button('복원 확인').disabled).toBe(true)
  expect(container.querySelector('[role=alert]')?.textContent).toContain('새로고침')
  await click('상태 새로고침')
  expect(get).toHaveBeenCalledTimes(2)
  expect(restore).toHaveBeenCalledTimes(1)
  expect(button('복원 확인').disabled).toBe(false)
})

it('연속 확인은 하나의 POST만 보내고 처리 중에는 변경과 재조회를 막는다', async () => {
  let finish!: (value: typeof restored) => void
  restore.mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
  get.mockResolvedValueOnce(item).mockResolvedValue({ ...item, status: 'RESTORED', restoredAt: restored.restoredAt })
  await mount()
  await click('오탐으로 복원')
  await act(async () => { const confirm = button('복원 확인'); confirm.click(); confirm.click() })
  expect(restore).toHaveBeenCalledTimes(1)
  expect(button('복원 중…').disabled).toBe(true)
  expect(button('새로고침').disabled).toBe(true)
  await act(async () => finish(restored))
  expect(get).toHaveBeenCalledTimes(2)
})

it.each(['RESTORED', 'DELETED', 'EXPIRED'] as const)('%s 상태에는 복원 버튼을 제공하지 않는다', async (status) => {
  get.mockResolvedValue({ ...item, status })
  await mount()
  expect([...container.querySelectorAll('button')].some((b) => b.textContent === '오탐으로 복원')).toBe(false)
  expect(restore).not.toHaveBeenCalled()
})

it('확인을 취소하면 복원 요청을 보내지 않는다', async () => {
  await mount()
  await click('오탐으로 복원')
  await setReason('취소할 사유')
  await click('취소')
  expect(restore).not.toHaveBeenCalled()
  expect(button('오탐으로 복원').disabled).toBe(false)
})

it('복원 성공 후 조회만 실패하면 복원 상태를 유지하고 POST를 다시 보내지 않는다', async () => {
  get.mockResolvedValueOnce(item).mockRejectedValue(new Error('GET failure'))
  await mount()
  await click('오탐으로 복원')
  await click('복원 확인')
  expect(container.textContent).toContain('RESTORED')
  expect(container.textContent).toContain('화이트리스트 반영을 완료했습니다')
  expect(container.querySelector('[role=alert]')?.textContent).toContain('최신 상세')
  get.mockResolvedValue({ ...item, status: 'RESTORED', restoredAt: restored.restoredAt })
  await click('다시 시도')
  expect(restore).toHaveBeenCalledTimes(1)
  expect(container.querySelector('[role=alert]')).toBeNull()
})

it('복원 응답을 기다리다 다른 파일로 이동하면 이전 성공을 새 파일에 표시하지 않는다', async () => {
  let finish!: (value: typeof restored) => void
  restore.mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
  get.mockImplementation(async (value) => value === id ? item : { ...item, quarantineId: value, originalFilename: 'other.txt', sha256: 'b'.repeat(64) })
  await mount()
  await click('오탐으로 복원')
  await click('복원 확인')
  await act(async () => container.querySelector<HTMLAnchorElement>('#test-next-file')!.click())
  await act(async () => finish(restored))
  expect(container.textContent).toContain('other.txt')
  expect(container.textContent).not.toContain('화이트리스트 반영을 완료했습니다')
  expect(container.textContent).not.toContain('RESTORED')
  expect(button('오탐으로 복원').disabled).toBe(false)
})

it.each(['success', 'failure'] as const)('파일을 바꾼 뒤 이전 GET의 늦은 %s를 무시한다', async (outcome) => {
  let finish!: (value: typeof item) => void
  let fail!: (cause: Error) => void
  const previous = new Promise<typeof item>((resolve, reject) => { finish = resolve; fail = reject })
  get.mockImplementation((value) => value === id ? previous : Promise.resolve({ ...item, quarantineId: value, originalFilename: 'other.txt' }))
  await mount()
  await act(async () => container.querySelector<HTMLAnchorElement>('#test-next-file')!.click())
  await act(async () => { if (outcome === 'success') finish(item); else fail(new Error('old GET failed')) })
  expect(container.textContent).toContain('other.txt')
  expect(container.textContent).not.toContain('sample.txt')
  expect(container.querySelector('[role=alert]')).toBeNull()
})

it('복원 영향을 확인한 뒤 사유를 전송하고 성공하면 상세를 다시 조회한다', async () => {
  get.mockResolvedValueOnce(item).mockResolvedValue({ ...item, status: 'RESTORED', restoredAt: restored.restoredAt })
  await mount()
  await click('오탐으로 복원')
  expect(restore).not.toHaveBeenCalled()
  expect(container.textContent).toContain('화이트리스트')
  expect(container.textContent).toContain('블랙리스트')
  expect(container.textContent).toContain('다운로드')
  await setReason('오탐 검토 완료')
  await click('복원 확인')
  expect(restore).toHaveBeenCalledExactlyOnceWith(id, '오탐 검토 완료')
  expect(get).toHaveBeenCalledTimes(2)
  expect(container.textContent).toContain('RESTORED')
  expect(container.textContent).toContain('화이트리스트 반영을 완료했습니다')
  expect([...container.querySelectorAll('button')].some((b) => b.textContent === '오탐으로 복원')).toBe(false)
})
