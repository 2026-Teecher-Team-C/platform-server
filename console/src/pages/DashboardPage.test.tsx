// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'
import { loadDashboard, type DashboardSnapshot } from '../lib/dashboard/load'
import { ApiError } from '../lib/api/client'

vi.mock('../lib/dashboard/load', () => ({ loadDashboard: vi.fn() }))
const auth = vi.hoisted(() => ({ role: 'ADMIN' as 'ADMIN' | 'ANALYST' }))
vi.mock('../app/useAuth', () => ({ useAuth: () => ({ user: { username: 'u', role: auth.role }, signOut: vi.fn() }) }))
const load = vi.mocked(loadDashboard)

const snapshot: DashboardSnapshot = {
  summary: {
    period: { from: '2026-11-01T05:02:00Z', to: '2026-11-08T05:02:00Z' },
    blocked: 48, blockedPreviousPeriod: 36,
    malicious: { total: 46, engine: 20, cache: 18, blacklist: 8 },
    failClose: 2, affectedAgents: 9, totalAgents: 42, inspected: 1922, blockRate: 0.025,
  },
  unacknowledged: {
    content: [{
      eventId: 'u1', createdAt: '2026-11-08T05:00:00Z', agentId: 'a1', hostname: 'DEV-031',
      filename: 'setup_installer.exe', reasonType: 'RULE', topRuleName: 'Suspicious_PE_Packer',
      extraRuleCount: 1, severity: 'HIGH',
    }],
    page: 0, size: 5, totalElements: 1, totalPages: 1,
  },
  threats: [{
    kind: 'RULE', ruleName: 'Suspicious_PE_Packer', severity: 'HIGH', affectedAgents: 4, blockCount: 17,
    firstSeenAt: '2026-10-29T01:00:00Z', lastSeenAt: '2026-11-08T04:50:00Z',
  }],
  engine: {
    state: 'HEALTHY', since: null, lastSuccessAt: '2026-11-08T05:01:00Z', lastFailureAt: null,
    failCloseSinceDown: 0, affectedAgentsSinceDown: 0, windowMinutes: 10,
  },
  daily: { stackBy: 'SOURCE', keys: ['ENGINE'], days: [{ date: '2026-11-08', counts: { ENGINE: 3 } }] },
  topAgents: [{ key: 'a1', label: 'DEV-031', blockCount: 14 }],
  topHosts: [{ key: 'evil.example', label: 'evil.example', blockCount: 9 }],
  recentBlocks: [{
    eventId: 'r1', agentId: 'a1', hostname: 'DEV-031', sha256: 'a'.repeat(64), filename: 'helper_tool.exe',
    mimeType: null, fileSize: null, decision: 'BLOCKED', decisionSource: 'ENGINE', similarityClassification: null, cacheHit: false,
    holdDurationMs: null, createdAt: '2026-11-08T05:00:00Z', severity: 'HIGH',
    topRuleName: 'Embedded_PowerShell_Loader', extraRuleCount: 0, acknowledged: false,
  }],
}

function deferred() {
  let resolve!: (value: DashboardSnapshot) => void
  let reject!: (error: Error) => void
  const promise = new Promise<DashboardSnapshot>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  load.mockReset()
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})
afterEach(async () => {
  await act(async () => root.unmount())
  container.remove()
  vi.useRealTimers()
})

async function mount() {
  await act(async () => root.render(<MemoryRouter><DashboardPage /></MemoryRouter>))
}
function counterValues() {
  return [...container.querySelectorAll('.dash-counter__value')].map((node) => node.textContent)
}
async function choosePeriod(value: string) {
  const select = [...container.querySelectorAll('label')]
    .find((node) => node.textContent?.startsWith('기간'))?.querySelector('select')
  if (!select) throw new Error('기간 select 없음')
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')!.set!.call(select, value)
    select.dispatchEvent(new Event('change', { bubbles: true }))
  })
}
async function click(text: string) {
  const button = [...container.querySelectorAll('button')].find((node) => node.textContent === text)
  if (!button) throw new Error('버튼 없음: ' + text)
  await act(async () => button.click())
}

describe('위협 대시보드', () => {
  it('섹션이 설계 순서대로 나온다', async () => {
    load.mockResolvedValue(snapshot)
    await mount()
    expect(container.querySelector('h1')?.textContent).toBe('위협 대시보드')
    expect(container.querySelector('.eyebrow')?.textContent).toBe('Dashboard')
    const titles = [...container.querySelectorAll('h2')].map((node) => node.textContent)
    expect(titles).toEqual(['확인 안 한 높은 심각도 차단', '위협별 현황', '일별 차단', '매칭 룰 Top 5', '차단 상위', '최근 차단'])
    // 미확인 차단은 헤더의 종 버튼 패널로 옮겼다.
    const bell = container.querySelector('.dashboard-header__controls .bell-button')!
    expect(bell.getAttribute('aria-label')).toBe('확인 안 한 높은 심각도 차단 1건')
    expect(container.querySelector('.dash-stack [aria-labelledby=unack-blocks-title]')).toBeNull()
    const counters = container.querySelector('.dash-counters')!
    const threats = container.querySelector('[aria-labelledby=threat-table-title]')!
    expect(counters.compareDocumentPosition(threats) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const rows = [...container.querySelectorAll('.dash-row')].map((row) =>
      [...row.querySelectorAll('h2')].map((node) => node.textContent))
    expect(rows).toEqual([['위협별 현황', '일별 차단'], ['매칭 룰 Top 5', '차단 상위'], ['최근 차단']])
    expect(counterValues()).toEqual(['48', '46', '2', '9', '1,922'])
    expect(container.querySelector('[role=status].live-status')?.textContent).toContain('5초마다 갱신 · ')
    expect(load).toHaveBeenCalledWith({ period: '7d', stackBy: 'SOURCE' }, expect.any(AbortSignal))
  })

  it('기간을 바꾸면 새 기간으로 다시 불러오고 이전 기간 숫자를 보여 주지 않는다', async () => {
    load.mockResolvedValueOnce(snapshot)
    await mount()
    expect(counterValues()).toContain('1,922')
    const pending = deferred()
    load.mockReturnValueOnce(pending.promise)
    await choosePeriod('30d')
    expect(load).toHaveBeenLastCalledWith({ period: '30d', stackBy: 'SOURCE' }, expect.any(AbortSignal))
    expect(counterValues().every((value) => value === '—')).toBe(true)
    expect(container.textContent).not.toContain('Suspicious_PE_Packer')
    expect(container.querySelector('.engine-badge')?.textContent).toContain('확인 불가')
    expect(container.querySelector('.live-status')?.textContent).toContain('불러오는 중')
    await act(async () => pending.resolve({ ...snapshot, summary: { ...snapshot.summary, inspected: 7000 } }))
    expect(counterValues()).toContain('7,000')
  })

  it('쌓기 기준만 바꿀 때도 이전 데이터를 남기지 않는다', async () => {
    load.mockResolvedValueOnce(snapshot)
    await mount()
    load.mockReturnValueOnce(deferred().promise)
    await click('룰별')
    expect(load).toHaveBeenLastCalledWith({ period: '7d', stackBy: 'RULE' }, expect.any(AbortSignal))
    expect(counterValues().every((value) => value === '—')).toBe(true)
  })

  it('같은 기간으로 되돌아와도 예전에 받은 숫자를 다시 보여 주지 않는다', async () => {
    load.mockResolvedValueOnce(snapshot)
    await mount()
    load.mockReturnValueOnce(deferred().promise).mockReturnValueOnce(deferred().promise)
    await choosePeriod('30d')
    await choosePeriod('7d')
    expect(load).toHaveBeenLastCalledWith({ period: '7d', stackBy: 'SOURCE' }, expect.any(AbortSignal))
    expect(counterValues().every((value) => value === '—')).toBe(true)
  })

  it('늦게 도착한 이전 기간 응답은 무시한다', async () => {
    const first = deferred()
    const second = deferred()
    load.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    await mount()
    await choosePeriod('today')
    const firstSignal = load.mock.calls[0][1]
    expect(firstSignal.aborted).toBe(true)
    await act(async () => first.resolve(snapshot))
    expect(counterValues().every((value) => value === '—')).toBe(true)
    await act(async () => second.resolve({ ...snapshot, summary: { ...snapshot.summary, blocked: 5 } }))
    expect(counterValues()[0]).toBe('5')
  })

  it('이전 기간의 실패는 새 기간 첫 응답 전에 경고로 남지 않는다', async () => {
    load.mockRejectedValueOnce(new Error('network'))
    await mount()
    expect(container.querySelector('[role=alert]')?.textContent).toContain('서버에서 데이터를 가져오지 못했습니다.')
    expect(container.querySelector('.live-status')?.textContent).toContain('연결 실패')
    load.mockReturnValueOnce(deferred().promise)
    await choosePeriod('30d')
    expect(container.querySelector('.dashboard-alert')).toBeNull()
    expect(container.querySelector('.live-status')?.textContent).toContain('불러오는 중')
  })

  it('갱신이 실패하면 데이터는 남기고 엔진 배지는 확인 불가로 바꾼다', async () => {
    vi.useFakeTimers()
    load.mockResolvedValueOnce(snapshot).mockRejectedValueOnce(new Error('network'))
    await mount()
    expect(container.querySelector('.engine-badge')?.textContent).toContain('탐지 엔진 정상')
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(load).toHaveBeenCalledTimes(2)
    expect(counterValues()).toContain('1,922')
    expect(container.textContent).toContain('Suspicious_PE_Packer')
    expect(container.querySelector('.engine-badge')?.textContent).toContain('엔진 상태 확인 불가')
    expect(container.querySelector('.live-status')?.textContent).toContain('갱신 실패 · 마지막 ')
    expect(container.querySelector('.live-status')?.classList.contains('is-stale')).toBe(true)
    expect(container.querySelector('[role=alert].dashboard-alert')?.textContent).toContain('서버에서 데이터를 가져오지 못했습니다.')
  })

  it('폴링으로 새로 들어온 최근 차단만 강조하고 기간을 바꾼 첫 응답은 강조하지 않는다', async () => {
    vi.useFakeTimers()
    const newer = {
      ...snapshot,
      recentBlocks: [{ ...snapshot.recentBlocks[0], eventId: 'r2' }, ...snapshot.recentBlocks],
    }
    load.mockResolvedValueOnce(snapshot).mockResolvedValueOnce(newer)
    await mount()
    expect(container.querySelectorAll('tr.is-new')).toHaveLength(0)
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(container.querySelectorAll('tr.is-new')).toHaveLength(1)
    load.mockResolvedValueOnce({ ...snapshot, recentBlocks: [{ ...snapshot.recentBlocks[0], eventId: 'r9' }] })
    await choosePeriod('30d')
    expect(container.querySelectorAll('tr.is-new')).toHaveLength(0)
  })

  it('서버에 대시보드 API가 없으면(404) 위젯 대신 안내를 보여 주고 폴링은 계속한다', async () => {
    vi.useFakeTimers()
    load.mockRejectedValueOnce(new ApiError(404, null)).mockResolvedValueOnce(snapshot)
    await mount()
    const notice = container.querySelector('.dashboard-notice')!
    expect(notice.textContent).toContain('위협 대시보드 API가 서버에 아직 없습니다')
    expect(notice.textContent).toContain('/api/admin/dashboard/*')
    expect(container.querySelector('.live-status')?.textContent).toContain('대시보드 API 없음')
    expect(container.querySelector('.live-status')?.textContent).not.toContain('연결 실패')
    expect(container.querySelector('.dash-stack')).toBeNull()
    expect(container.querySelector('.dashboard-alert')).toBeNull()
    expect(container.querySelectorAll('.dash-panel')).toHaveLength(0)
    expect([...container.querySelectorAll('h2')].map((n) => n.textContent)).toEqual(['위협 대시보드 API가 서버에 아직 없습니다'])
    const links = [...container.querySelectorAll('a')]
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['/events'])
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(container.querySelector('.dashboard-notice')).toBeNull()
    expect(counterValues()).toContain('1,922')
  })

  it('이전 기간의 404는 새 기간 첫 응답 전에 안내로 남지 않는다', async () => {
    load.mockRejectedValueOnce(new ApiError(404, null))
    await mount()
    expect(container.querySelector('.dashboard-notice')).not.toBeNull()
    load.mockReturnValueOnce(deferred().promise)
    await choosePeriod('30d')
    expect(container.querySelector('.dashboard-notice')).toBeNull()
    expect(container.querySelector('.live-status')?.textContent).toContain('불러오는 중')
  })

  it('500은 안내가 아니라 기존 연결 실패로 보여 준다', async () => {
    load.mockRejectedValueOnce(new ApiError(500, null))
    await mount()
    expect(container.querySelector('.dashboard-notice')).toBeNull()
    expect(container.querySelector('[role=alert]')?.textContent).toContain('서버에서 데이터를 가져오지 못했습니다.')
    expect(container.querySelector('.live-status')?.textContent).toContain('연결 실패')
    expect(container.querySelectorAll('h2').length).toBeGreaterThan(0)
  })

  it('ANALYST에게는 종 패널에 확인 버튼을 보여 주지 않는다', async () => {
    auth.role = 'ANALYST'
    try {
      load.mockResolvedValue(snapshot)
      await mount()
      const panel = container.querySelector('.bell-panel')!
      expect(panel.querySelector('tbody tr')).not.toBeNull()
      expect([...panel.querySelectorAll('button')].map((b) => b.textContent)).not.toContain('확인')
    } finally {
      auth.role = 'ADMIN'
    }
  })

  it('ADMIN에게는 종 패널에 확인 버튼을 보여 준다', async () => {
    load.mockResolvedValue(snapshot)
    await mount()
    const panel = container.querySelector('.bell-panel')!
    expect([...panel.querySelectorAll('button')].map((b) => b.textContent)).toContain('확인')
  })
})
