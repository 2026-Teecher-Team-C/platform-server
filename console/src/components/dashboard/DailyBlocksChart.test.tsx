// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DailyBlocksChart } from './DailyBlocksChart'
import type { DailyBlocks } from '../../lib/api/dashboard'

let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })

async function mount(data: DailyBlocks | null, onChange = vi.fn(), stackBy: 'SOURCE' | 'RULE' = 'SOURCE') {
  await act(async () => root.render(<DailyBlocksChart data={data} stackBy={stackBy} onStackByChange={onChange} />))
  return onChange
}

const data = (counts: Record<string, number>[]): DailyBlocks => ({
  stackBy: 'SOURCE',
  keys: ['ENGINE', 'CACHE'],
  days: counts.map((c, i) => ({ date: `2026-11-0${i + 1}`, counts: c })),
})

describe('DailyBlocksChart', () => {
  it('쌓기 기준 버튼을 누르면 onStackByChange가 불린다', async () => {
    const onChange = await mount(data([{ ENGINE: 1 }]))
    const buttons = [...container.querySelectorAll('button')]
    expect(buttons.map((b) => b.textContent)).toEqual(['판정 소스별', '룰별'])
    expect(buttons[0].getAttribute('aria-pressed')).toBe('true')
    await act(async () => buttons[1].click())
    expect(onChange).toHaveBeenCalledWith('RULE')
  })

  it('막대 높이는 최대 일 합계 대비 비율이다', async () => {
    await mount(data([{ ENGINE: 2, CACHE: 2 }, { ENGINE: 1, CACHE: 1 }]))
    const bars = container.querySelectorAll<HTMLElement>('.dash-daily__bar')
    expect(bars[0].getAttribute('aria-label')).toBe('11/1 차단 4건')
    expect(bars[1].getAttribute('aria-label')).toBe('11/2 차단 2건')
    expect(bars[0].style.height).toBe('100%')
    expect(bars[1].style.height).toBe('50%')
    expect(container.textContent).toContain('엔진')
    expect(container.textContent).toContain('판정 이력')
  })

  it('모든 날이 0이면 NaN 없이 빈 상태를 보여 준다', async () => {
    await mount(data([{}, { ENGINE: 0 }]))
    expect(container.textContent).toContain('이 기간에 차단이 없습니다')
    expect(container.innerHTML).not.toContain('NaN')
    container.querySelectorAll<HTMLElement>('[style]').forEach((el) => {
      expect(el.style.height === '' || el.style.height === '0%').toBe(true)
    })
  })

  it('data가 null이면 제목만 보인다', async () => {
    await mount(null)
    expect(container.textContent).toContain('일별 차단')
    expect(container.textContent).not.toContain('이 기간에 차단이 없습니다')
  })
})
