// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { TopRules } from './TopRules'
import type { ThreatRow } from '../../lib/api/dashboard'

function threat(over: Partial<ThreatRow>): ThreatRow {
  return {
    kind: 'RULE', ruleName: 'r', severity: 'HIGH', affectedAgents: 1, blockCount: 1,
    firstSeenAt: '2026-11-01T00:00:00Z', lastSeenAt: '2026-11-02T00:00:00Z', ...over,
  }
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

async function mount(rows: ThreatRow[] | null) {
  await act(async () => root.render(<MemoryRouter><TopRules rows={rows} /></MemoryRouter>))
}
const names = () => [...container.querySelectorAll('.dash-top__label')].map((n) => n.textContent)

describe('TopRules', () => {
  it('블랙리스트를 뺀 룰을 차단 수 순으로 5개까지 막대로 보여 준다', async () => {
    await mount([
      threat({ ruleName: 'A', blockCount: 1 }),
      threat({ kind: 'BLACKLIST', ruleName: null, blockCount: 30 }),
      threat({ ruleName: 'B', blockCount: 10, severity: 'CRITICAL' }),
      threat({ ruleName: 'C', blockCount: 5 }),
      threat({ ruleName: 'D', blockCount: 4 }),
      threat({ ruleName: 'E', blockCount: 3 }),
      threat({ ruleName: 'F', blockCount: 2 }),
    ])
    expect(container.querySelector('h2')?.textContent).toBe('매칭 룰 Top 5')
    expect(names()).toEqual(['B', 'C', 'D', 'E', 'F'])
    const widths = [...container.querySelectorAll<HTMLElement>('.dash-top__bar')].map((b) => b.style.width)
    expect(widths).toEqual(['100%', '50%', '40%', '30%', '20%'])
    expect(container.querySelector('.severity-badge')?.textContent).toBe('Critical')
    expect(container.querySelector('a')?.getAttribute('href')).toBe('/rulesets')
  })

  it('매칭된 룰이 없으면 빈 문구를 보여 준다', async () => {
    await mount([threat({ kind: 'BLACKLIST', ruleName: null })])
    expect(container.textContent).toContain('이 기간에 매칭된 룰이 없습니다')
  })

  it('불러오기 전에는 목록도 빈 문구도 없다', async () => {
    await mount(null)
    expect(names()).toEqual([])
    expect(container.textContent).not.toContain('이 기간에 매칭된 룰이 없습니다')
  })
})
