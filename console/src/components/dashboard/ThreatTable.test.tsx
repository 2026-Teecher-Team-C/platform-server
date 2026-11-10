// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { ThreatTable } from './ThreatTable'
import type { EngineStatus, ThreatRow } from '../../lib/api/dashboard'

const now = new Date(2026, 10, 8, 12, 0, 0)
const local = (h: number, m: number, d = 8) => new Date(2026, 10, d, h, m, 0).toISOString()

function engine(over: Partial<EngineStatus> = {}): EngineStatus {
  return {
    state: 'HEALTHY', since: null, lastSuccessAt: local(11, 52), lastFailureAt: null,
    windowMinutes: 10, failCloseSinceDown: 0, affectedAgentsSinceDown: 0, ...over,
  }
}
function row(over: Partial<ThreatRow> = {}): ThreatRow {
  return {
    kind: 'RULE', ruleName: 'Emotet_Loader', severity: 'CRITICAL', affectedAgents: 3, blockCount: 14,
    firstSeenAt: local(9, 0, 3), lastSeenAt: local(11, 50), ...over,
  }
}
const down = () => engine({
  state: 'DOWN', since: local(11, 42), lastSuccessAt: local(11, 41), lastFailureAt: local(11, 59),
  failCloseSinceDown: 7, affectedAgentsSinceDown: 4,
})

let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })

async function mount(rows: ThreatRow[] | null, eng: EngineStatus | null, stale = false) {
  await act(async () => root.render(
    <MemoryRouter><ThreatTable rows={rows} engine={eng} stale={stale} now={now} /></MemoryRouter>,
  ))
}
const badge = () => container.querySelector('.engine-badge')

describe('ThreatTable', () => {
  it('엔진 상태 세 가지를 문구로 구분한다', async () => {
    await mount([], engine())
    expect(badge()?.textContent).toBe('● 탐지 엔진 정상')
    expect(badge()?.className).toContain('engine-badge--ok')
    await mount([], down())
    expect(badge()?.textContent).toBe('● 탐지 엔진 응답 없음 · 18분째')
    expect(badge()?.className).toContain('engine-badge--down')
    await mount([], engine({ state: 'IDLE' }))
    expect(badge()?.textContent).toBe('● 최근 검사 없음 (마지막 정상 판정 11:52)')
    expect(badge()?.className).toContain('engine-badge--unknown')
    await mount([], engine({ state: 'IDLE', lastSuccessAt: null }))
    expect(badge()?.textContent).toBe('● 최근 검사 없음 (정상 판정 기록 없음)')
  })

  it('갱신에 실패하면 마지막 상태가 정상이어도 확인 불가로 표시한다', async () => {
    await mount([], engine(), true)
    expect(badge()?.textContent).toBe('● 엔진 상태 확인 불가')
    expect(container.textContent).not.toContain('탐지 엔진 정상')
    expect(badge()?.className).toContain('engine-badge--unknown')
    await mount([], null)
    expect(badge()?.textContent).toBe('● 엔진 상태 확인 불가')
  })

  it('갱신 실패 중이면 DOWN이어도 경고 줄을 보이지 않는다', async () => {
    await mount([row()], down(), true)
    expect(badge()?.textContent).toBe('● 엔진 상태 확인 불가')
    expect(container.querySelector('[role="alert"]')).toBeNull()
  })

  it('엔진 장애 때 표는 그대로 두고 경고와 장애 차단 목록 링크를 띄운다', async () => {
    await mount([row()], down())
    expect(container.querySelector('[role="alert"]')?.textContent).toContain(
      '새 파일 다운로드가 장애 차단되고 있습니다 · 장애 이후 7건 · 영향 PC 4대 · 마지막 정상 판정 11:41',
    )
    const link = [...container.querySelectorAll('a')].find((a) => a.textContent === '장애 차단 목록 →')
    expect(link?.getAttribute('href')).toBe('/events?decision=FAIL_CLOSE')
    expect(container.querySelectorAll('tbody tr')).toHaveLength(1)
    await mount([row()], engine())
    expect(container.querySelector('[role="alert"]')).toBeNull()
  })

  it('DOWN 경고는 정상 판정 기록이 없으면 그렇게 쓰고 since가 없으면 분수를 생략한다', async () => {
    await mount([], engine({ ...down(), lastSuccessAt: null, since: null }))
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('정상 판정 기록 없음')
    expect(badge()?.textContent).toBe('● 탐지 엔진 응답 없음')
  })

  it('표 열과 값을 보여 준다', async () => {
    await mount([row()], engine())
    expect([...container.querySelectorAll('th')].map((h) => h.textContent)).toEqual(
      ['위협 (룰)', '심각도', '영향 PC', '차단', '처음', '마지막'],
    )
    const cells = [...container.querySelectorAll('tbody td')].map((c) => c.textContent)
    expect(cells).toEqual(['Emotet_Loader', 'Critical', '3', '14', '11/3', '10분 전'])
    expect(container.querySelector('.severity-badge--danger')).not.toBeNull()
  })

  it('블랙리스트 줄과 빈 표 문구', async () => {
    await mount([row({ kind: 'BLACKLIST', ruleName: null })], engine())
    expect(container.textContent).toContain('관리자 등록 블랙리스트')
    await mount([], engine())
    expect(container.textContent).toContain('이 기간에 차단된 위협이 없습니다')
    expect(container.querySelector('table')).toBeNull()
  })

  it('데이터 전에는 제목과 배지만 보인다', async () => {
    await mount(null, null)
    expect(container.textContent).toContain('위협별 현황')
    expect(container.querySelector('table')).toBeNull()
    expect(container.textContent).not.toContain('이 기간에 차단된 위협이 없습니다')
  })

  it('차단 칸에 가장 많은 위협 대비 막대를 그린다', async () => {
    await mount([row({ blockCount: 14 }), row({ ruleName: 'B', blockCount: 7 })], engine())
    const widths = [...container.querySelectorAll<HTMLElement>('.dash-inline-bar__fill')].map((b) => b.style.width)
    expect(widths).toEqual(['100%', '50%'])
    expect([...container.querySelectorAll('.dash-inline-bar')].map((n) => n.textContent)).toEqual(['14', '7'])
  })

  it('10줄까지만 보여 준다', async () => {
    await mount(Array.from({ length: 12 }, (_, i) => row({ ruleName: 'R' + i })), engine())
    expect(container.querySelectorAll('tbody tr')).toHaveLength(10)
  })
})
