import { useState } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../../app/useAuth'
import { allowedMenus } from '../../lib/auth/roles'
import { NAV_GROUPS, NAV_ITEMS } from './navigation'

const SIDEBAR_COLLAPSED_KEY = 'console.sidebar.collapsed'

// 접은 상태는 이 브라우저에서만 기억하는 편의 기능이다. 저장소를 못 쓰면 펼친 상태로 시작한다.
function readSidebarCollapsed() {
  try {
    return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === 'true'
  } catch {
    return false
  }
}

function writeSidebarCollapsed(value: boolean) {
  try {
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(value))
  } catch {
    // 저장하지 못해도 현재 화면에서는 접힘 상태가 유지된다.
  }
}

export function Sidebar() {
  const navigate = useNavigate()
  const { user, signOut } = useAuth()
  const [collapsedGroups, setCollapsedGroups] = useState<Record<string, boolean>>({})
  const [sidebarCollapsed, setSidebarCollapsed] = useState(readSidebarCollapsed)

  // 이 사용자의 역할로 볼 수 있는 메뉴 key 목록을 구한다.
  // 로그인 정보가 없거나 권한 표에 없는 역할이면 빈 목록이 된다(= 메뉴 없음).
  // 볼 메뉴가 하나도 없는 그룹은 제목까지 숨긴다.
  const allowed = new Set(allowedMenus(user?.role))
  const visibleGroups = NAV_GROUPS.map((group) => ({
    title: group,
    items: NAV_ITEMS.filter((item) => item.group === group && allowed.has(item.key)),
  })).filter((group) => group.items.length > 0)

  function handleLogout() {
    // 로컬 인증은 즉시 지우고 로그인 화면으로 이동한다. 서버 폐기 실패 안내는 AuthProvider가 이어서 표시한다.
    void signOut()
    navigate('/login', { replace: true })
  }

  function toggleGroup(title: string) {
    setCollapsedGroups((prev) => ({ ...prev, [title]: !prev[title] }))
  }

  function toggleSidebar() {
    const next = !sidebarCollapsed
    setSidebarCollapsed(next)
    writeSidebarCollapsed(next)
  }

  return (
    <aside className={'sidebar' + (sidebarCollapsed ? ' collapsed' : '')}>
      <button
        className="sidebar-collapse"
        type="button"
        onClick={toggleSidebar}
        aria-expanded={!sidebarCollapsed}
        aria-label={sidebarCollapsed ? '사이드바 펼치기' : '사이드바 접기'}
        title={sidebarCollapsed ? '사이드바 펼치기' : '사이드바 접기'}
      >
        <span aria-hidden="true">{sidebarCollapsed ? '›' : '‹'}</span>
      </button>

      <div className="brand">
        <span className="brand-mark">T</span>
        {!sidebarCollapsed && (
          <div>
            <strong>Team-c</strong>
            <span>Admin Console</span>
          </div>
        )}
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

      {!sidebarCollapsed && (
        <nav className="sidebar-nav" aria-label="관리 콘솔 메뉴">
          {visibleGroups.map((group) => {
            const open = !collapsedGroups[group.title]
            const listId = 'nav-group-' + group.title
            return (
              <div className="nav-group" key={group.title}>
                <button
                  type="button"
                  className="nav-group-toggle"
                  aria-expanded={open}
                  aria-controls={listId}
                  onClick={() => toggleGroup(group.title)}
                >
                  {group.title}
                  <span className="nav-group-chevron" aria-hidden="true" />
                </button>
                {open && (
                  <div className="nav-group-items" id={listId}>
                    {group.items.map((item) => (
                      <NavLink
                        key={item.to}
                        to={item.to}
                        end={item.to === '/agents'}
                        className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
                      >
                        {item.label}
                      </NavLink>
                    ))}
                  </div>
                )}
              </div>
            )
          })}
        </nav>
      )}
    </aside>
  )
}
