import { Outlet } from 'react-router-dom'
import { Sidebar } from './Sidebar'

export function AppLayout() {
  return (
    <div className="app-shell">
      <Sidebar />
      <div className="app-main">
        <header className="topbar">
          <div>
            <p className="eyebrow">Teecher Team C</p>
            <strong>Security Console</strong>
          </div>
          <div className="topbar-user" aria-label="current user placeholder">
            <span className="status-dot" />
            인증 연동 예정
          </div>
        </header>
        <main className="page-content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
