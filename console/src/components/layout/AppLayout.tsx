import { Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../../app/useAuth'
import { Sidebar } from './Sidebar'

export function AppLayout() {
  const navigate = useNavigate()
  const { user, signOut } = useAuth()

  async function handleLogout() {
    await signOut()
    navigate('/login', { replace: true })
  }

  return (
    <div className="app-shell">
      <Sidebar />
      <div className="app-main">
        <header className="topbar">
          <div>
            <p className="eyebrow">Teecher Team C</p>
            <strong>Security Console</strong>
          </div>
          <div className="topbar-user" aria-label="현재 로그인 사용자">
            <span className="status-dot" />
            <span className="topbar-user-info">
              <strong>{user?.username ?? '-'}</strong>
              <small>{user?.role ?? '-'}</small>
            </span>
            <button className="topbar-logout" type="button" onClick={handleLogout}>
              로그아웃
            </button>
          </div>
        </header>
        <main className="page-content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
