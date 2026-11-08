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
    expect(container.querySelectorAll('.dash-daily__bar')).toHaveLength(0)
    expect(container.querySelector('[style]')).toBeNull()
  })

  it('합계 0인 날은 높이 0%에 세그먼트가 없고 다른 날은 비율대로 쌓인다', async () => {
    await mount(data([{ ENGINE: 0, CACHE: 0 }, { ENGINE: 2, CACHE: 2 }]))
    const bars = container.querySelectorAll<HTMLElement>('.dash-daily__bar')
    expect(bars[0].getAttribute('style')).toContain('height: 0%')
    expect(bars[0].children).toHaveLength(0)
    const segs = [...bars[1].children].map((el) => el.getAttribute('style'))
    expect(segs).toEqual(['height: 50%;', 'height: 50%;'])
    expect(bars[1].getAttribute('style')).toContain('height: 100%')
    for (const el of container.querySelectorAll('[style]')) {
      expect(el.getAttribute('style')).not.toMatch(/NaN|Infinity/)
    }
  })

  it('범례는 키 라벨을 매핑하고 룰 이름은 그대로 보인다', async () => {
    await mount({
      stackBy: 'SOURCE',
      keys: ['ENGINE', 'CACHE', 'BLACKLIST', 'FAIL_CLOSE', 'OTHER'],
      days: [{ date: '2026-11-01', counts: { ENGINE: 1 } }],
    })
    const legend = [...container.querySelectorAll('.dash-daily__legend li')].map((li) => li.textContent)
    expect(legend).toEqual(['엔진', '판정 이력', '블랙리스트', '장애 차단', '기타'])
    await mount({
      stackBy: 'RULE',
      keys: ['Emotet_Loader', 'Other.Rule'],
      days: [{ date: '2026-11-01', counts: { Emotet_Loader: 1 } }],
    }, vi.fn(), 'RULE')
    const rules = [...container.querySelectorAll('.dash-daily__legend li')].map((li) => li.textContent)
    expect(rules).toEqual(['Emotet_Loader', 'Other.Rule'])
  })

  it('색 class는 순번이 아니라 키 의미로 정해 FAIL_CLOSE·BLACKLIST는 두 모드에서 같은 색이다', async () => {
    const classOf = (key: string) => {
      const index = [...container.querySelectorAll('.dash-daily__legend li')].findIndex((li) => li.textContent === key)
      return container.querySelectorAll('.dash-daily__swatch')[index].className
    }
    await mount({
      stackBy: 'SOURCE',
      keys: ['ENGINE', 'CACHE', 'BLACKLIST', 'FAIL_CLOSE', 'OTHER'],
      days: [{ date: '2026-11-01', counts: { ENGINE: 1, FAIL_CLOSE: 1 } }],
    })
    const sourceFailClose = classOf('장애 차단')
    const sourceBlacklist = classOf('블랙리스트')
    expect(sourceFailClose).toContain('dash-daily__seg--fail-close')
    expect(sourceBlacklist).toContain('dash-daily__seg--blacklist')
    expect(classOf('기타')).toContain('dash-daily__seg--other')
    const failSeg = container.querySelector('.dash-daily__bar .dash-daily__seg--fail-close')
    expect(failSeg).not.toBeNull()
    await mount({
      stackBy: 'RULE',
      keys: ['Rule_A', 'BLACKLIST', 'Rule_B', 'FAIL_CLOSE'],
      days: [{ date: '2026-11-01', counts: { Rule_A: 1, FAIL_CLOSE: 1 } }],
    }, vi.fn(), 'RULE')
    expect(classOf('장애 차단')).toBe(sourceFailClose)
    expect(classOf('블랙리스트')).toBe(sourceBlacklist)
    expect(classOf('Rule_A')).toContain('dash-daily__seg--rule-0')
    expect(classOf('Rule_B')).toContain('dash-daily__seg--rule-1')
  })

  it('날짜 라벨은 막대 트랙 밖에 있다', async () => {
    await mount(data([{ ENGINE: 2 }, { ENGINE: 1 }]))
    const col = container.querySelector('.dash-daily__col')!
    const track = col.querySelector('.dash-daily__track')!
    expect(track.querySelector('.dash-daily__bar')).not.toBeNull()
    expect(track.querySelector('.dash-daily__label')).toBeNull()
    expect(col.querySelector(':scope > .dash-daily__label')?.textContent).toBe('11/1')
  })

  it('data가 null이면 제목만 보인다', async () => {
    await mount(null)
    expect(container.textContent).toContain('일별 차단')
    expect(container.textContent).not.toContain('이 기간에 차단이 없습니다')
  })
})
