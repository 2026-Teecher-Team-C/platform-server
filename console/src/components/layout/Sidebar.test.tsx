// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Sidebar } from './Sidebar'

const auth = vi.hoisted(() => ({ role: 'ADMIN' as 'ADMIN' | 'ANALYST' | 'VIEWER' }))
vi.mock('../../app/useAuth', () => ({
  useAuth: () => ({ user: { username: 'admin', role: auth.role }, signOut: vi.fn() }),
}))

let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  auth.role = 'ADMIN'
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})
afterEach(async () => {
  await act(async () => root.unmount())
  container.remove()
})

async function mount(path = '/dashboard') {
  await act(async () =>
    root.render(
      <MemoryRouter initialEntries={[path]}>
        <Sidebar />
      </MemoryRouter>,
    ),
  )
}

const groupTitles = () => [...container.querySelectorAll('.nav-group-toggle')].map((b) => b.textContent?.trim())
const linksIn = (title: string) => {
  const group = [...container.querySelectorAll('.nav-group')].find(
    (g) => g.querySelector('.nav-group-toggle')?.textContent?.trim() === title,
  )
  return [...(group?.querySelectorAll('a') ?? [])].map((a) => a.textContent)
}

describe('Sidebar', () => {
  it('메뉴를 탐지·대응·정책·운영·관리 그룹으로 나눠 보여준다', async () => {
    await mount()
    expect(groupTitles()).toEqual(['탐지', '대응', '정책', '운영', '관리'])
    expect(linksIn('탐지')).toEqual(['위협 대시보드', '탐지 이벤트'])
    expect(linksIn('대응')).toEqual(['격리 파일'])
    expect(linksIn('정책')).toEqual(['검사 정책', '화이트리스트', '블랙리스트', 'YARA 룰셋'])
    expect(linksIn('운영')).toEqual(['PC 현황'])
    expect(linksIn('관리')).toEqual(['등록 토큰 발급', '감사 로그'])
  })

  it('ANALYST에게는 권한 없는 메뉴를 숨기고, 메뉴가 모두 빠진 그룹은 제목도 숨긴다', async () => {
    auth.role = 'ANALYST'
    await mount()
    expect(groupTitles()).toEqual(['탐지', '대응', '정책', '운영'])
    expect(linksIn('정책')).toEqual(['검사 정책', '블랙리스트', 'YARA 룰셋'])
  })

  it('볼 메뉴가 없는 역할(VIEWER)도 로그아웃 버튼은 보인다', async () => {
    auth.role = 'VIEWER'
    await mount()
    expect(groupTitles()).toEqual([])
    expect(container.querySelector('.sidebar-logout')).not.toBeNull()
  })

  it('그룹 제목을 누르면 접히고 다시 누르면 펼쳐진다', async () => {
    await mount()
    const toggle = container.querySelector<HTMLButtonElement>('.nav-group-toggle')!
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    await act(async () => toggle.click())
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(linksIn('탐지')).toEqual([])
    await act(async () => toggle.click())
    expect(linksIn('탐지')).toEqual(['위협 대시보드', '탐지 이벤트'])
  })

  it('사이드바 접기 버튼을 누르면 메뉴가 숨고 다시 누르면 보인다', async () => {
    await mount()
    const aside = container.querySelector('aside')!
    const button = () => container.querySelector<HTMLButtonElement>('.sidebar-collapse')!
    expect(button().getAttribute('aria-expanded')).toBe('true')
    await act(async () => button().click())
    expect(aside.classList.contains('collapsed')).toBe(true)
    expect(button().getAttribute('aria-expanded')).toBe('false')
    expect(button().getAttribute('aria-label')).toBe('사이드바 펼치기')
    expect(container.querySelector('.sidebar-nav')).toBeNull()
    await act(async () => button().click())
    expect(aside.classList.contains('collapsed')).toBe(false)
    expect(groupTitles()).toHaveLength(5)
  })

  it('접은 상태를 기억했다가 다시 열 때 그대로 보여준다', async () => {
    await mount()
    await act(async () => container.querySelector<HTMLButtonElement>('.sidebar-collapse')!.click())
    await act(async () => root.unmount())
    root = createRoot(container)
    await mount()
    expect(container.querySelector('aside')!.classList.contains('collapsed')).toBe(true)
    localStorage.clear()
  })

  it('현재 경로의 메뉴만 active다 (PC 현황은 등록 토큰 경로에서 active가 아니다)', async () => {
    await mount('/agents/enrollment-token')
    const active = [...container.querySelectorAll('a.active')].map((a) => a.textContent)
    expect(active).toEqual(['등록 토큰 발급'])
  })
})
