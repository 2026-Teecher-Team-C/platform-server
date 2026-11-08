// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Link, MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EventsPage } from './EventsPage'
import { getDownloadEvents } from '../lib/api/events'

vi.mock('../lib/api/events', () => ({ getDownloadEvents: vi.fn() }))
const get = vi.mocked(getDownloadEvents)
const item = {
  eventId: 'e1', createdAt: '2026-11-08T01:00:00Z', filename: 'a.exe', hostname: 'QA-PC',
  decision: 'BLOCKED' as const, decisionSource: null, cacheHit: false, fileSize: 1024, holdDurationMs: 10,
  severity: 'HIGH' as const,
}
const empty = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }
let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  get.mockReset()
  get.mockResolvedValue({ ...empty, content: [item as never], totalElements: 1, totalPages: 1 })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })
async function mount(url: string) {
  await act(async () => root.render(<MemoryRouter initialEntries={[url]}><Link to="/events?decision=FAIL_CLOSE">장애만</Link><EventsPage /></MemoryRouter>))
}
function arg(n: number) {
  const value = get.mock.calls[n]?.[0]
  if (!value) throw new Error('호출 없음: ' + n)
  return value
}
function button(text: string) {
  const result = [...container.querySelectorAll('button')].find((node) => node.textContent === text)
  if (!result) throw new Error('버튼 없음: ' + text)
  return result
}

describe('탐지 이벤트 URL 필터', () => {
  it('대시보드 링크의 조건으로 처음 조회한다', async () => {
    await mount('/events?decision=BLOCKED&minSeverity=HIGH&acknowledged=false')
    expect(get).toHaveBeenCalledTimes(1)
    expect(arg(0)).toMatchObject({ decision: 'BLOCKED', minSeverity: 'HIGH', acknowledged: false, page: 0 })
    expect(container.textContent).toContain('대시보드에서 건 조건: 차단 · High 이상 · 미확인')
    expect(container.querySelector('select')?.value).toBe('BLOCKED')
    expect(container.querySelector('.severity-badge--danger')?.textContent).toBe('High')
  })

  it('차단과 장애 차단을 함께 건다', async () => {
    await mount('/events?decision=BLOCKED&decision=FAIL_CLOSE')
    expect(arg(0).decision).toEqual(['BLOCKED', 'FAIL_CLOSE'])
    expect(container.textContent).toContain('대시보드에서 건 조건: 차단·장애 차단')
    expect(container.querySelector('select')?.value).toBe('')
  })

  it('알 수 없는 값은 보내지 않는다', async () => {
    await mount('/events?decision=NOPE&minSeverity=EXTREME&acknowledged=maybe')
    const a = arg(0)
    expect(a.decision).toBeUndefined()
    expect(a.minSeverity).toBeUndefined()
    expect(a.acknowledged).toBeUndefined()
    expect(container.textContent).not.toContain('대시보드에서 건 조건')
  })

  it('조건 해제를 누르면 조건 없이 다시 조회한다', async () => {
    await mount('/events?decision=FAIL_CLOSE')
    await act(async () => button('조건 해제').click())
    expect(get).toHaveBeenCalledTimes(2)
    const a = arg(1)
    expect(a.decision).toBeUndefined()
    expect(a.minSeverity).toBeUndefined()
    expect(a.page).toBe(0)
    expect(container.textContent).not.toContain('대시보드에서 건 조건')
    expect(container.querySelector('select')?.value).toBe('')
  })

  it('쿼리가 없으면 기존처럼 조건 없이 조회한다', async () => {
    await mount('/events')
    expect(get).toHaveBeenCalledTimes(1)
    expect(arg(0)).toEqual({ page: 0, size: 20 })
    expect(container.textContent).not.toContain('대시보드에서 건 조건')
  })

  it('심각도가 없는 이벤트는 —로 보인다', async () => {
    get.mockResolvedValue({ ...empty, content: [{ ...item, severity: null } as never], totalElements: 1, totalPages: 1 })
    await mount('/events')
    expect(container.textContent).toContain('심각도')
    expect(container.querySelector('.severity-badge')).toBeNull()
    const cells = [...container.querySelectorAll('tbody td')].map((td) => td.textContent)
    expect(cells[4]).toBe('—')
  })

  it('URL 조건이 있어도 조회하면 폼 값만으로 한 번 더 조회한다', async () => {
    await mount('/events?decision=BLOCKED&minSeverity=HIGH&acknowledged=false')
    const input = container.querySelector('input[placeholder="Agent ID"]') as HTMLInputElement
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, 'agent-1')
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await act(async () => button('조회').click())
    await act(async () => { await Promise.resolve() })
    expect(get).toHaveBeenCalledTimes(2)
    expect(arg(1).agentId).toBe('agent-1')
    // 폼의 판정 select는 URL 값으로 채워져 있으므로 폼 값 BLOCKED만 간다. URL 전용 조건은 버린다.
    expect(arg(1).decision).toBe('BLOCKED')
    expect(arg(1).minSeverity).toBeUndefined()
    expect(arg(1).acknowledged).toBeUndefined()
    expect(container.textContent).not.toContain('대시보드에서 건 조건')
    expect(input.value).toBe('agent-1')
  })

  it('마운트된 뒤 URL 쿼리가 바뀌면 다시 조회한다', async () => {
    await mount('/events')
    const link = [...container.querySelectorAll('a')].find((a) => a.textContent === '장애만')!
    await act(async () => link.click())
    expect(get).toHaveBeenCalledTimes(2)
    expect(arg(1).decision).toBe('FAIL_CLOSE')
    expect(container.textContent).toContain('대시보드에서 건 조건: 장애 차단')
  })
})
