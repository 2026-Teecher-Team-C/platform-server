// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { TopSources } from './TopSources'
import type { TopSource } from '../../lib/api/dashboard'

const agents: TopSource[] = [
  { key: 'a1', label: 'DEV-031', blockCount: 4 },
  { key: 'a2', label: 'OPS-012', blockCount: 2 },
]
const hosts: TopSource[] = [{ key: 'evil.example', label: 'evil.example', blockCount: 3 }]

let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })

async function mount(a: TopSource[] | null, h: TopSource[] | null) {
  await act(async () => root.render(<MemoryRouter><TopSources agents={a} hosts={h} /></MemoryRouter>))
}
const labels = () => [...container.querySelectorAll('.dash-top__label')].map((n) => n.textContent)
const toggle = (text: string) => [...container.querySelectorAll('button')].find((b) => b.textContent === text)!

describe('TopSources', () => {
  it('처음에는 PC 기준으로 막대와 PC 현황 링크를 보여 준다', async () => {
    await mount(agents, hosts)
    expect(container.querySelector('h2')?.textContent).toBe('차단 상위')
    expect(labels()).toEqual(['DEV-031', 'OPS-012'])
    expect(toggle('PC').getAttribute('aria-pressed')).toBe('true')
    expect(container.querySelector('a')?.getAttribute('href')).toBe('/agents')
  })

  it('도메인을 누르면 다시 불러오지 않고 도메인 목록으로 바뀐다', async () => {
    await mount(agents, hosts)
    await act(async () => toggle('도메인').click())
    expect(labels()).toEqual(['evil.example'])
    expect(toggle('도메인').getAttribute('aria-pressed')).toBe('true')
    expect(container.querySelector('a')).toBeNull()
  })

  it('막대 너비는 목록에서 가장 큰 값 대비 비율이다', async () => {
    await mount(agents, hosts)
    const widths = [...container.querySelectorAll<HTMLElement>('.dash-top__bar')].map((b) => b.style.width)
    expect(widths).toEqual(['100%', '50%'])
  })

  it('불러오기 전에는 목록도 빈 문구도 없다', async () => {
    await mount(null, null)
    expect(labels()).toEqual([])
    expect(container.textContent).not.toContain('이 기간에 차단이 없습니다')
  })

  it('차단이 없으면 빈 문구를 보여 준다', async () => {
    await mount([], [])
    expect(container.textContent).toContain('이 기간에 차단이 없습니다')
  })
})
