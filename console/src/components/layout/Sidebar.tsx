import { NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../../app/useAuth'

const navigation = [
  { to: '/dashboard', label: '대시보드' },
  { to: '/events', label: '다운로드 이벤트' },
  { to: '/quarantine', label: '격리 파일' },
  { to: '/agents', label: 'PC 현황' },
  { to: '/agents/enrollment-token', label: '등록 토큰 발급', adminOnly: true },
  { to: '/whitelist', label: '화이트리스트' },
  { to: '/blacklist', label: '블랙리스트' },
  { to: '/policies', label: '정책' },
  { to: '/rulesets', label: 'YARA 룰셋' },
  { to: '/audit-logs', label: '감사 로그' },
]

export function Sidebar() {
  const navigate = useNavigate()
  const { user, signOut } = useAuth()
  const visibleNavigation = navigation.filter((item) => !item.adminOnly || user?.role === 'ADMIN')

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
