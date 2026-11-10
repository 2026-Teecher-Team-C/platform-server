import { NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../../app/useAuth'
import { allowedMenus } from '../../lib/auth/roles'
import { NAV_ITEMS } from './navigation'

export function Sidebar() {
  const navigate = useNavigate()
  const { user, signOut } = useAuth()

  // 이 사용자의 역할로 볼 수 있는 메뉴 key 목록을 구한다.
  // 로그인 정보가 없거나 권한 표에 없는 역할이면 빈 목록이 된다(= 메뉴 없음).
  const allowed = new Set(allowedMenus(user?.role))
  const visibleNavigation = NAV_ITEMS.filter((item) => allowed.has(item.key))

  function handleLogout() {
    signOut()
    navigate('/login', { replace: true })
  }

  return (
    <aside className="sidebar">
      <div className="brand">
        <span className="brand-mark">T</span>
        <div>
          <strong>Team-c</strong>
          <span>Admin Console</span>
        </div>
        {/* 로그아웃 버튼은 역할과 상관없이 항상 보인다. 메뉴가 하나도 없는 역할도 나갈 수 있어야 한다. */}
        <button
          className="sidebar-logout"
          type="button"
          onClick={handleLogout}
          aria-label="로그아웃"
          title={'로그아웃' + (user ? ' (' + user.username + ')' : '')}
        >
          ⏻
        </button>
      </div>

      <nav className="sidebar-nav" aria-label="관리 콘솔 메뉴">
        {visibleNavigation.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/agents'}
            className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
          >
            {item.label}
          </NavLink>
        ))}
      </nav>

      <div className="sidebar-footer">
        <span>Console Sprint 2</span>
        <small>Auth · Deployment · Observability</small>
      </div>
    </aside>
  )
}