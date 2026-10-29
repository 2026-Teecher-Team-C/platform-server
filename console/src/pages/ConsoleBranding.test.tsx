import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { Sidebar } from '../components/layout/Sidebar'
import { WhitelistPage } from './WhitelistPage'
import { RulesetsPage } from './RulesetsPage'
import { DashboardPage } from './DashboardPage'

// 사이드바 표시만 확인한다. 실제 로그인이나 로그아웃 요청은 보내지 않는다.
vi.mock('../app/useAuth', () => ({
  useAuth: () => ({ user: null, signOut: vi.fn() }),
}))

describe('관리 화면 영문 제목과 프로젝트 이름', () => {
  it('대시보드 제목 위에 영문 라벨을 표시하고 갱신 상태 안내를 유지한다', () => {
    const html = renderToStaticMarkup(<MemoryRouter><DashboardPage /></MemoryRouter>)
    expect(html).toContain('<p class="eyebrow">Dashboard</p>')
    expect(html).toContain('class="live-status" role="status"')
  })

  it('화이트리스트 제목 위에 공통 영문 라벨을 표시한다', () => {
    const html = renderToStaticMarkup(<MemoryRouter><WhitelistPage /></MemoryRouter>)
    expect(html).toContain('<p class="eyebrow">Whitelist</p>')
  })

  it('YARA 제목 위에 공통 영문 라벨을 표시한다', () => {
    const html = renderToStaticMarkup(<MemoryRouter><RulesetsPage /></MemoryRouter>)
    expect(html).toContain('<p class="eyebrow">YARA rulesets</p>')
  })

  it('왼쪽 상단 프로젝트 이름은 Team-c로 표시한다', () => {
    const html = renderToStaticMarkup(<MemoryRouter><Sidebar /></MemoryRouter>)
    expect(html).toContain('<strong>Team-c</strong>')
    expect(html).not.toContain('Teecher')
  })
})
