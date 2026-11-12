// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { EnrollmentTokenPage } from './EnrollmentTokenPage'
import { createEnrollmentToken, type CreateEnrollmentTokenResponse } from '../lib/api/agents'

vi.mock('../app/useAuth', () => ({ useAuth: () => ({ user: { role: 'ADMIN' } }) }))
vi.mock('../lib/api/agents', () => ({ createEnrollmentToken: vi.fn() }))
const issue = vi.mocked(createEnrollmentToken)
let container: HTMLDivElement
let root: Root
beforeEach(async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  issue.mockReset()
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
  await act(async () => root.render(<EnrollmentTokenPage />))
})
afterEach(async () => { await act(async () => root.unmount()); container.remove(); vi.unstubAllGlobals() })
async function submit(times = 1) {
  await act(async () => {
    for (let i = 0; i < times; i++) container.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  })
}
function deferred() {
  let resolve!: (value: CreateEnrollmentTokenResponse) => void
  let reject!: (error: Error) => void
  const promise = new Promise<CreateEnrollmentTokenResponse>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
// 테스트용 원문은 실행 중 생성하며 실패 출력에 원문을 넣지 않는다.
const result = (): CreateEnrollmentTokenResponse => ({ token: crypto.randomUUID(), expiresAt: '2030-01-01T00:00:00Z' })
it('ADMIN은 기본 정책으로 발급한다', async () => {
  issue.mockResolvedValue(result())
  expect(container.querySelector('form')).not.toBeNull()
  await submit()
  expect(issue).toHaveBeenCalledWith({})
  expect(container.querySelector('code')).not.toBeNull()
  expect(container.textContent).toContain('지금만 확인')
  expect(container.textContent).toContain('60분')
})
it('같은 렌더 안의 중복 제출도 한 번만 발급한다', async () => {
  const pending = deferred(); issue.mockReturnValue(pending.promise)
  await submit(2)
  expect(issue).toHaveBeenCalledTimes(1)
  await act(async () => pending.resolve(result()))
})
it('복사 실패를 알리되 지금만 보이는 원문을 유지한다', async () => {
  issue.mockResolvedValue(result())
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
  await submit()
  await act(async () => container.querySelector<HTMLButtonElement>('.token-value-box button')!.click())
  expect(container.textContent).toContain('복사 실패')
  expect(container.querySelector('code')).not.toBeNull()
})
it('언마운트 뒤 완료된 발급은 다시 마운트한 화면에 나타나지 않는다', async () => {
  const pending = deferred(); issue.mockReturnValue(pending.promise)
  await submit()
  await act(async () => root.render(<div />))
  await act(async () => root.render(<EnrollmentTokenPage />))
  await act(async () => pending.resolve(result()))
  expect(container.querySelector('code')).toBeNull()
})

async function input(value: string) {
  await act(async () => {
    const node = container.querySelector('input')!
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(node, value)
    node.dispatchEvent(new Event('input', { bubbles: true }))
  })
}
it.each(['0', '1441', '1.5'])('잘못된 유효 시간 %s는 요청 전에 차단한다', async (value) => {
  await input(value)
  await submit()
  expect(issue).not.toHaveBeenCalled()
  expect(container.querySelector('[role=alert]')).not.toBeNull()
})
it('실패하면 입력과 오류를 보존하고 같은 값으로 재시도한다', async () => {
  issue.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(result())
  await input('120')
  await submit()
  expect(container.querySelector('input')!.value).toBe('120')
  expect(container.querySelector('[role=alert]')).not.toBeNull()
  await submit()
  expect(issue).toHaveBeenLastCalledWith({ expiresInMinutes: 120 })
  expect(container.querySelector('[role=alert]')).toBeNull()
})
it('새 발급 뒤 도착한 이전 복사 실패는 새 결과의 복사 상태를 바꾸지 않는다', async () => {
  let reject!: (error: Error) => void
  const promise = new Promise<void>((_, no) => { reject = no })
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => promise } })
  issue.mockImplementation(async () => result())
  await submit()
  await act(async () => container.querySelector<HTMLButtonElement>('.token-value-box button')!.click())
  await submit()
  await act(async () => reject(new Error('late failure')))
  expect(container.querySelector('.token-value-box button')?.textContent).toBe('복사')
})
