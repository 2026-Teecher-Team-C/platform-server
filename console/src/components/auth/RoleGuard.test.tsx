import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthContext } from '../../app/auth-context'
import type { AuthContextValue } from '../../app/auth-context'
import type { AdminRole, CurrentAdmin } from '../../lib/api/auth'
import { RoleGuard } from './RoleGuard'

// 주어진 역할로 로그인한 상태에서, 주어진 주소로 들어갔을 때의 화면을 HTML 문자열로 돌려준다.
// 실제 페이지 대신 "○○ 화면"이라는 문구만 그리는 가짜 라우트를 쓴다.
function renderAt(path: string, role: AdminRole) {
  const user: CurrentAdmin = {
    adminId: 'test-admin',
    username: 'tester',
    role,
    isActive: true,
    lastLoginAt: null,
  }
  const value: AuthContextValue = {
    user,
    isInitializing: false,
    isAuthenticated: true,
    signIn: async () => user,
    signOut: async () => {},
    authNotice: '',
  }

  return renderToStaticMarkup(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<RoleGuard />}>
            <Route path="dashboard" element={<p>대시보드 화면</p>} />
            <Route path="whitelist" element={<p>화이트리스트 화면</p>} />
            <Route path="*" element={<p>없는 주소 화면</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

describe('RoleGuard', () => {
  it.each(['ANALYST', 'USER'] as const)('샘플 상세도 서버의 ADMIN 전용 정책에 맞춰 %s를 막는다', (role) => {
    expect(renderAt('/samples/' + 'a'.repeat(64), role)).toContain('접근 권한이 없습니다.')
  })

  it('ADMIN은 샘플 상세 경로를 통과한다', () => {
    expect(renderAt('/samples/' + 'a'.repeat(64), 'ADMIN')).not.toContain('접근 권한이 없습니다.')
  })
  it('권한이 있는 주소는 그대로 보여 준다', () => {
    const html = renderAt('/dashboard', 'ANALYST')

    expect(html).toContain('대시보드 화면')
    expect(html).not.toContain('접근 권한이 없습니다.')
  })

  it('권한이 없는 주소로 직접 들어오면 접근 권한 없음 화면을 보여 준다', () => {
    const html = renderAt('/whitelist', 'ANALYST')

    expect(html).toContain('접근 권한이 없습니다.')
    expect(html).not.toContain('화이트리스트 화면')
  })

  it('주소를 대소문자만 바꿔 들어와도 막는다', () => {
    const html = renderAt('/Whitelist', 'ANALYST')

    expect(html).toContain('접근 권한이 없습니다.')
    expect(html).not.toContain('화이트리스트 화면')
  })

  it('ADMIN은 화이트리스트 주소에 들어갈 수 있다', () => {
    const html = renderAt('/whitelist', 'ADMIN')

    expect(html).toContain('화이트리스트 화면')
    expect(html).not.toContain('접근 권한이 없습니다.')
  })

  it('메뉴에 없는 주소는 권한 검사 없이 통과시킨다(404 화면 몫)', () => {
    const html = renderAt('/something-else', 'ANALYST')

    expect(html).toContain('없는 주소 화면')
    expect(html).not.toContain('접근 권한이 없습니다.')
  })

  it('접근 권한 없음 화면은 갈 수 있는 첫 메뉴로 이동하는 링크를 준다', () => {
    const html = renderAt('/whitelist', 'ANALYST')

    expect(html).toContain('대시보드(으)로 이동')
    expect(html).toContain('로그아웃')
  })

  it('갈 수 있는 메뉴가 하나도 없는 역할에게는 이동 링크 없이 로그아웃만 준다', () => {
    const html = renderAt('/dashboard', 'USER')

    expect(html).toContain('접근 권한이 없습니다.')
    expect(html).not.toContain('(으)로 이동')
    expect(html).toContain('로그아웃')
  })
})