// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { UnacknowledgedBlocks } from './UnacknowledgedBlocks'
import { acknowledgeDownloadEvent, type UnacknowledgedBlock } from '../../lib/api/dashboard'
import type { PageResponse } from '../../lib/api/types'

vi.mock('../../lib/api/dashboard', () => ({ acknowledgeDownloadEvent: vi.fn() }))
const ack = vi.mocked(acknowledgeDownloadEvent)

function block(id: string, over: Partial<UnacknowledgedBlock> = {}): UnacknowledgedBlock {
  return {
    eventId: id, createdAt: '2026-11-04T14:02:00', agentId: 'a-' + id, hostname: 'PC-' + id,
    filename: 'setup-' + id + '.exe', reasonType: 'RULE', topRuleName: 'Emotet_Loader',
    extraRuleCount: 0, severity: 'HIGH', ...over,
  }
}
function page(content: UnacknowledgedBlock[], totalElements = content.length): PageResponse<UnacknowledgedBlock> {
  return { content, page: 0, size: 20, totalElements, totalPages: 1 }
}
function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: Error) => void
  const promise = new Promise<T>((a, b) => { resolve = a; reject = b })
  return { promise, resolve, reject }
}
const okResult = { eventId: 'x', acknowledgedBy: 'admin', acknowledgedAt: '2026-11-04T14:03:00' }

let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  ack.mockReset()
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })

async function mount(p: PageResponse<UnacknowledgedBlock> | null, loaded = true) {
  await act(async () => root.render(
    <MemoryRouter><UnacknowledgedBlocks page={p} loaded={loaded} /></MemoryRouter>,
  ))
}
function confirmButtons() {
  return [...container.querySelectorAll('button')].filter((b) => b.textContent === '확인' || b.textContent === '처리 중')
}
const badge = () => container.querySelector('.dash-badge')?.textContent
const rows = () => container.querySelectorAll('tbody tr')

describe('UnacknowledgedBlocks', () => {
  it('확인을 누르면 응답 전까지 버튼이 잠기고 성공하면 행이 사라지고 배지가 줄어든다', async () => {
    const d = deferred<typeof okResult>()
    ack.mockReturnValueOnce(d.promise)
    await mount(page([block('1'), block('2')]))
    expect(badge()).toBe('2')
    expect(container.textContent).toContain('11/4 14:02')
    await act(async () => confirmButtons()[0].click())
    expect(ack).toHaveBeenCalledWith('1')
    expect(confirmButtons()[0].disabled).toBe(true)
    expect(confirmButtons()[0].textContent).toBe('처리 중')
    expect(rows()).toHaveLength(2)
    await act(async () => d.resolve(okResult))
    expect(rows()).toHaveLength(1)
    expect(badge()).toBe('1')
    expect(container.textContent).not.toContain('PC-1')
  })

  it('서로 다른 행의 확인을 동시에 눌러도 각각 처리된다', async () => {
    const a = deferred<typeof okResult>()
    const b = deferred<typeof okResult>()
    ack.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    await mount(page([block('1'), block('2')]))
    await act(async () => confirmButtons()[0].click())
    await act(async () => confirmButtons()[1].click())
    expect(confirmButtons().every((x) => x.disabled)).toBe(true)
    await act(async () => a.resolve(okResult))
    await act(async () => b.resolve(okResult))
    expect(rows()).toHaveLength(0)
    expect(badge()).toBe('0')
  })

  it('확인이 실패하면 행이 남고 오류를 알린다', async () => {
    ack.mockRejectedValueOnce(new Error('boom'))
    await mount(page([block('1')]))
    await act(async () => confirmButtons()[0].click())
    expect(rows()).toHaveLength(1)
    expect(confirmButtons()[0].disabled).toBe(false)
    expect(confirmButtons()[0].textContent).toBe('확인')
    expect(container.querySelector('[role="alert"]')?.textContent).toBe('확인 처리에 실패했습니다. 다시 시도해 주세요.')
    expect(badge()).toBe('1')
    ack.mockResolvedValueOnce(okResult)
    await act(async () => confirmButtons()[0].click())
    expect(container.querySelector('[role="alert"]')).toBeNull()
  })

  it('새 page에 같은 이벤트가 없으면 숨김 목록을 비운다', async () => {
    ack.mockResolvedValueOnce(okResult)
    await mount(page([block('1'), block('2')], 2))
    await act(async () => confirmButtons()[0].click())
    expect(badge()).toBe('1')
    await mount(page([block('2'), block('3'), block('4')], 3))
    expect(badge()).toBe('3')
    expect(rows()).toHaveLength(3)
  })

  it('새 page에도 같은 이벤트가 남아 있으면 계속 숨기고 배지에서 뺀다', async () => {
    ack.mockResolvedValueOnce(okResult)
    await mount(page([block('1'), block('2')], 2))
    await act(async () => confirmButtons()[0].click())
    await mount(page([block('1'), block('2')], 2))
    expect(rows()).toHaveLength(1)
    expect(badge()).toBe('1')
  })

  it('미확인이 없으면 한 줄로 접는다', async () => {
    await mount(page([], 0))
    expect(container.textContent).toContain('확인할 차단이 없습니다')
    expect(container.querySelector('table')).toBeNull()
    expect(container.textContent).not.toContain('전체 보기')
  })

  it('불러오기 전에는 배지가 — 이고 빈 목록 문구를 보이지 않는다', async () => {
    await mount(null, false)
    expect(badge()).toBe('—')
    expect(container.textContent).not.toContain('확인할 차단이 없습니다')
    expect(container.textContent).toContain('확인 안 한 높은 심각도 차단')
  })

  it('블랙리스트 차단은 관리자 등록 블랙리스트로, 파일이 없으면 —로 표시한다', async () => {
    await mount(page([block('1', { reasonType: 'BLACKLIST', topRuleName: null, filename: null, severity: 'CRITICAL' })]))
    expect(container.textContent).toContain('관리자 등록 블랙리스트')
    const cells = [...container.querySelectorAll('tbody td')].map((c) => c.textContent)
    expect(cells).toContain('—')
    expect(container.querySelector('.severity-badge--danger')?.textContent).toBe('Critical')
  })

  it('전체 보기 링크는 높은 심각도 미확인 필터를 건다', async () => {
    await mount(page([block('1')]))
    const link = [...container.querySelectorAll('a')].find((a) => a.textContent === '전체 보기 →')
    expect(link?.getAttribute('href')).toBe('/events?decision=BLOCKED&minSeverity=HIGH&acknowledged=false')
  })
})
