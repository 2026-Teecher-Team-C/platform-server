// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { CounterRow } from './CounterRow'
import type { DashboardSummary } from '../../lib/api/dashboard'
import type { PeriodKey } from '../../lib/dashboard/format'

const summary: DashboardSummary = {
  period: { from: '2026-10-31T15:00:00Z', to: '2026-11-07T15:00:00Z' },
  blocked: 48, blockedPreviousPeriod: 36,
  malicious: { total: 46, engine: 20, cache: 18, blacklist: 8 },
  failClose: 2, affectedAgents: 9, totalAgents: 42, inspected: 1922, blockRate: 0.025,
}

let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })

async function mount(s: DashboardSummary | null, period: PeriodKey = '7d') {
  await act(async () => root.render(<CounterRow summary={s} period={period} />))
}

describe('CounterRow', () => {
  it('카운터는 기간에 맞는 비교 문구와 출처별 내역을 보여 준다', async () => {
    await mount(summary, '7d')
    const text = container.textContent ?? ''
    for (const label of ['차단', '악성 판정', '장애 차단', '영향받은 PC', '검사한 다운로드']) {
      expect(text).toContain(label)
    }
    expect(text).toContain('전주 대비 +12')
    expect(text).toContain('엔진 20 · 판정 이력 18 · 블랙리스트 8')
    expect(text).toContain('검사 불가')
    expect(text).toContain('전체 42대 중')
    expect(text).toContain('차단율 2.5%')
    expect(text).toContain('1,922')
    await mount(summary, 'today')
    expect(container.textContent).toContain('직전 같은 시간 대비 +12')
  })

  it('데이터 전이면 숫자 자리에 —를 보이고 부가 문구는 없다', async () => {
    await mount(null)
    expect(container.querySelectorAll('.dash-counter__value')).toHaveLength(5)
    expect([...container.querySelectorAll('.dash-counter__value')].every((v) => v.textContent === '—')).toBe(true)
    expect(container.textContent).not.toContain('전주 대비')
    expect(container.textContent).not.toContain('전체')
  })
})
