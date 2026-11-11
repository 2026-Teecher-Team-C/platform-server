import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { expect, it } from 'vitest'
import { AuthContext } from '../../app/auth-context'
import { AppLayout } from './AppLayout'

it.each(['ADMIN', 'ANALYST', 'VIEWER'] as const)('역할 %s에서도 상단 바를 되살리지 않고 사이드바의 로그아웃과 관리 메뉴를 유지한다', (role) => {
  const user = { adminId: 'layout-test-admin', username: 'layout-test-user', role, isActive: true, lastLoginAt: null }
  const html = renderToStaticMarkup(
    <AuthContext.Provider value={{ user, isInitializing: false, authNotice: '', isAuthenticated: true, signIn: async () => user, signOut: async () => {} }}>
      <MemoryRouter><AppLayout /></MemoryRouter>
    </AuthContext.Provider>,
  )
  expect(html).toContain('sidebar-logout')
  expect(html).toContain('aria-label="로그아웃"')
  expect(html).toContain('layout-test-user')
  expect(html).toContain('/agents/enrollment-token')
  expect(html).toContain('/whitelist')
  expect(html).toContain('/audit-logs')
  expect(html).not.toContain('topbar')
})
