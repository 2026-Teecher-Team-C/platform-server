// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentsPage } from './AgentsPage'
import { getAgents, type AgentListResponse } from '../lib/api/agents'

vi.mock('../lib/api/agents', () => ({ getAgents: vi.fn() }))
const get = vi.mocked(getAgents)
const item = {
  agentId: '10000000-0000-4000-8000-000000000001', hostname: 'QA-PC',
  osPlatform: 'WINDOWS' as const, agentVersion: '0.3.1', status: 'ACTIVE' as const, lastHeartbeatAt: null,
}
const response: AgentListResponse = { content: [item], page: 0, size: 20, totalElements: 41, totalPages: 3 }
function deferred() {
  let resolve!: (value: AgentListResponse) => void
  let reject!: (error: Error) => void
  const promise = new Promise<AgentListResponse>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  get.mockReset()
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })
async function mount() { await act(async () => root.render(<AgentsPage />)) }
function button(text: string) {
  const result = [...container.querySelectorAll('button')].find((node) => node.textContent === text)
  if (!result) throw new Error('버튼 없음: ' + text)
  return result
}
async function click(text: string) { await act(async () => button(text).click()) }
async function change(label: string, value: string) {
  const control = [...container.querySelectorAll('label')].find((node) => node.textContent?.startsWith(label))?.querySelector('input,select')
  if (!control) throw new Error('필터 없음: ' + label)
  await act(async () => {
    const proto = control.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLSelectElement.prototype
    Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(control, value)
    control.dispatchEvent(new Event(control.tagName === 'INPUT' ? 'input' : 'change', { bubbles: true }))
  })
}

describe('PC 현황', () => {
  it('조회 실패에도 이전 표를 유지하되 새 조건으로 이전 표를 페이지 이동하지 않는다', async () => {
    get.mockResolvedValueOnce(response).mockRejectedValueOnce(new Error('network'))
    await mount()
    await change('호스트 이름', 'NEW')
    await click('조회')
    expect(container.textContent).toContain(item.hostname)
    expect(container.querySelector('[role=alert]')?.textContent).toContain('이전 조회 결과')
    expect(button('다음').disabled).toBe(true)
    const pending = deferred()
    get.mockReturnValueOnce(pending.promise)
    await click('다시 시도')
    expect(container.textContent).toContain(item.hostname)
    await act(async () => pending.resolve({ ...response, content: [{ ...item, hostname: 'NEW-PC' }] }))
    expect(container.textContent).toContain('NEW-PC')
    expect(container.querySelector('[role=alert]')).toBeNull()
  })

  it('마지막 페이지가 없어지면 존재하는 마지막 페이지를 다시 조회한다', async () => {
    get.mockResolvedValueOnce(response).mockImplementation(async (query) => ({
      ...response, page: query?.page ?? 0, content: query?.page === 1 ? [] : [item], totalElements: 1, totalPages: 1,
    }))
    await mount()
    await click('다음')
    expect(get.mock.calls.map(([query]) => query?.page)).toEqual([0, 1, 0])
    expect(container.querySelector('.events-pagination')?.textContent).toContain('1 / 1페이지')
  })

  it('빈 결과도 재조회 중에는 유지하고 같은 조회의 중복 실행은 막는다', async () => {
    get.mockResolvedValueOnce({ ...response, content: [], totalElements: 0, totalPages: 0 })
    await mount()
    const pending = deferred()
    get.mockReturnValueOnce(pending.promise)
    await click('새로고침')
    await click('새로고침')
    await click('조회')
    expect(get).toHaveBeenCalledTimes(2)
    expect(container.textContent).toContain('조건에 맞는 PC가 없습니다')
    await act(async () => pending.resolve(response))
  })

  it('재조회 진행 안내는 200ms 뒤 표시하고 응답이 오면 없앤다', async () => {
    vi.useFakeTimers()
    try {
      get.mockResolvedValueOnce(response)
      await mount()
      const pending = deferred()
      get.mockReturnValueOnce(pending.promise)
      await click('새로고침')
      expect(container.textContent).toContain(item.hostname)
      await act(async () => vi.advanceTimersByTimeAsync(199))
      expect(container.querySelector('[role=status]')).toBeNull()
      await act(async () => vi.advanceTimersByTimeAsync(1))
      expect(container.querySelector('[role=status]')?.textContent).toContain('조회 중')
      await act(async () => pending.resolve(response))
      expect(container.querySelector('[role=status]')).toBeNull()
    } finally { vi.useRealTimers() }
  })

  it.each(['success', 'error'] as const)('이전 요청의 늦은 %s는 최신 결과를 덮어쓰지 않는다', async (outcome) => {
    const older = deferred()
    get.mockReturnValueOnce(older.promise).mockResolvedValue(response)
    await mount()
    await change('호스트 이름', 'QA')
    await click('조회')
    expect(container.textContent).toContain(item.hostname)
    await act(async () => {
      if (outcome === 'success') older.resolve({ ...response, content: [{ ...item, hostname: 'OLD-PC' }] })
      else older.reject(new Error('old failure'))
    })
    expect(container.textContent).toContain(item.hostname)
    expect(container.textContent).not.toContain('OLD-PC')
    expect(container.querySelector('[role=alert]')).toBeNull()
    expect(container.querySelector('[role=status]')).toBeNull()
  })

  it('이전 응답은 최신 요청의 로딩도 끝내지 않는다', async () => {
    const older = deferred()
    const newer = deferred()
    get.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    await mount()
    await change('호스트 이름', 'NEW')
    await click('조회')
    await act(async () => older.resolve(response))
    expect(container.querySelector('[role=status]')?.textContent).toContain('불러오는 중')
    await act(async () => newer.resolve(response))
    expect(container.textContent).toContain(item.hostname)
  })
  it('빈 결과는 실패와 구분하고 다시 조회할 수 있다', async () => {
    get.mockResolvedValue({ ...response, content: [], totalElements: 0, totalPages: 0 })
    await mount()
    expect(container.textContent).toContain('조건에 맞는 PC가 없습니다')
    expect(container.querySelector('[role=alert]')).toBeNull()
    expect(button('다음').disabled).toBe(true)
    await click('새로고침')
    expect(get).toHaveBeenCalledTimes(2)
  })

  it('조회 실패 시 오류와 재시도를 표시하고 재시도 중에는 로딩을 표시한다', async () => {
    get.mockRejectedValue(new Error('network'))
    await mount()
    expect(container.querySelector('[role=alert]')?.textContent).toContain('불러오지 못했습니다')
    const pending = deferred()
    get.mockReturnValue(pending.promise)
    await click('다시 시도')
    expect(container.querySelector('[role=status]')?.textContent).toContain('불러오는 중')
    expect(container.querySelector('[role=alert]')).toBeNull()
    expect(button('다음').disabled).toBe(true)
    await act(async () => pending.resolve(response))
    expect(container.textContent).toContain(item.hostname)
    expect(get).toHaveBeenLastCalledWith({ page: 0, size: 20 })
  })
  it('입력 중에는 재조회하지 않고 조회 시 결합 필터, 첫 페이지를 요청한다', async () => {
    get.mockResolvedValue(response)
    await mount()
    await click('다음')
    const count = get.mock.calls.length
    await change('호스트 이름', '  LAB_%  ')
    await change('상태', 'REVOKED')
    await change('운영체제', 'LINUX')
    expect(get).toHaveBeenCalledTimes(count)
    await click('조회')
    expect(get).toHaveBeenLastCalledWith({ hostname: 'LAB_%', status: 'REVOKED', osPlatform: 'LINUX', page: 0, size: 20 })
    await change('호스트 이름', ' ')
    await change('상태', '')
    await change('운영체제', '')
    await click('조회')
    expect(get).toHaveBeenLastCalledWith({ page: 0, size: 20 })
    await click('조회')
    expect(get).toHaveBeenCalledTimes(count + 3)
  })
  it('페이지 이동과 크기 변경은 서버에 요청하고 크기 변경은 첫 페이지로 돌아간다', async () => {
    get.mockResolvedValue(response)
    await mount()
    get.mockResolvedValue({ ...response, page: 1 })
    await click('다음')
    expect(get).toHaveBeenLastCalledWith({ page: 1, size: 20 })
    await click('이전')
    expect(get).toHaveBeenLastCalledWith({ page: 0, size: 20 })
    await click('다음')
    get.mockResolvedValue({ ...response, size: 50, totalPages: 1 })
    await change('페이지 크기', '50')
    expect(get).toHaveBeenLastCalledWith({ page: 0, size: 50 })
    expect(button('이전').disabled).toBe(true)
    expect(button('다음').disabled).toBe(true)
    await change('페이지 크기', '100')
    expect(get).toHaveBeenLastCalledWith({ page: 0, size: 100 })
  })
  it('페이지 전환 중에는 이전 결과와 페이지 번호를 유지하고 이동 버튼을 잠근다', async () => {
    get.mockResolvedValueOnce(response)
    await mount()
    const pending = deferred()
    get.mockReturnValueOnce(pending.promise)
    await click('다음')
    expect(container.textContent).toContain(item.hostname)
    expect(container.querySelector('.events-pagination')?.textContent).toContain('1 / 3페이지')
    expect(button('다음').disabled).toBe(true)
    expect(button('이전').disabled).toBe(true)
    await act(async () => pending.resolve({ ...response, page: 1 }))
    expect(button('이전').disabled).toBe(false)
  })

  it('null이 아닌 하트비트는 수신 시각으로 표시한다', async () => {
    const heartbeat = '2026-10-28T01:00:00Z'
    get.mockResolvedValue({ ...response, content: [{ ...item, lastHeartbeatAt: heartbeat }] })
    await mount()
    expect(container.textContent).toContain(new Date(heartbeat).toLocaleString('ko-KR'))
    expect(container.textContent).not.toContain('미수신')
  })

  it('목록을 조회하고 null 하트비트는 미수신으로 표시하며 상세 링크를 만들지 않는다', async () => {
    const pending = deferred()
    get.mockReturnValue(pending.promise)
    await mount()
    expect(get).toHaveBeenCalledWith({ page: 0, size: 20 })
    expect(container.querySelector('[role=status]')?.textContent).toContain('불러오는 중')
    await act(async () => pending.resolve(response))
    for (const value of [item.hostname, item.agentId, item.osPlatform, item.agentVersion, item.status, '미수신']) {
      expect(container.textContent).toContain(value)
    }
    expect(container.querySelector('a')).toBeNull()
    expect(button('이전').disabled).toBe(true)
    expect(button('다음').disabled).toBe(false)
  })
})
