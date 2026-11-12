import { Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../../app/useAuth'
import { canAccess, canManage } from '../../lib/auth/roles'
import { ForbiddenPage } from '../../pages/ForbiddenPage'
import { findNavItemByPath } from '../layout/navigation'

// 로그인한 사용자의 역할로 현재 주소에 들어갈 수 있는지 확인하는 가드다.
// router.tsx에서 모든 페이지 라우트의 부모로 한 번만 끼워 넣는다.
//
// - 권한이 있으면 자식 페이지를 그대로 보여 준다(<Outlet />).
// - 권한이 없으면 접근 권한 없음 화면을 보여 준다. 주소는 바뀌지 않고, 사이드바는 그대로 보인다.
//
// 로그인 여부는 이 가드 바깥의 ProtectedRoute가 이미 확인했다.
// 그래서 여기서는 user가 있다고 보고 역할만 검사한다.
export function RoleGuard() {
  const { user } = useAuth()
  const { pathname } = useLocation()

  // 현재 주소가 사이드바의 어느 메뉴에 속하는지 찾는다. (상세 화면도 같은 메뉴로 본다.)
  const menu = findNavItemByPath(pathname)

  // 메뉴 목록에 없는 주소(404 등)는 권한 검사 대상이 아니므로 그대로 통과시킨다.
  // 샘플 조회는 새 서버 권한표의 기본 규칙(ADMIN 전용)을 따른다. 메뉴가 없어도 가드를 생략하지 않는다.
  const samplePath = pathname.toLowerCase().startsWith('/samples/')
  if ((samplePath && !canManage(user?.role)) || (menu && !canAccess(user?.role, menu.key))) {
    return <ForbiddenPage />
  }

  return <Outlet />
}