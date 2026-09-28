import { NavLink } from 'react-router-dom'

const navigation = [
  { to: '/dashboard', label: '대시보드' },
  { to: '/events', label: '다운로드 이벤트' },
  { to: '/quarantine', label: '격리 파일' },
  { to: '/agents', label: 'PC 현황' },
  { to: '/whitelist', label: '화이트리스트' },
  { to: '/blacklist', label: '블랙리스트' },
  { to: '/policies', label: '정책' },
  { to: '/rulesets', label: 'YARA 룰셋' },
  { to: '/audit-logs', label: '감사 로그' },
]

export function Sidebar() {
  return (
    <aside className="sidebar">
      <div className="brand">
        <span className="brand-mark">T</span>
        <div>
          <strong>Teecher</strong>
          <span>Admin Console</span>
        </div>
      </div>

      <nav className="sidebar-nav" aria-label="관리 콘솔 메뉴">
        {navigation.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
          >
            {item.label}
          </NavLink>
        ))}
      </nav>

      <div className="sidebar-footer">
        <span>Console Sprint 1</span>
        <small>Mock-first development</small>
      </div>
    </aside>
  )
}
