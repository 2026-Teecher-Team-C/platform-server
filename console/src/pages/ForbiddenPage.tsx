import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../app/useAuth'
import { NAV_ITEMS } from '../components/layout/navigation'
import { allowedMenus } from '../lib/auth/roles'

// 권한이 없는 주소로 들어왔을 때 보여 주는 화면이다.
// 5번 파일(라우트 가드)이 이 화면을 쓰고, 6번 파일(API 403 처리)에서도 같은 화면을 쓴다.
// 사이드바 옆 영역(<main>)에 그려지므로 사이드바는 그대로 보인다.
export function ForbiddenPage() {
  const navigate = useNavigate()
  const { user, signOut } = useAuth()

  // 이 사용자가 갈 수 있는 첫 번째 메뉴를 찾는다.
  // 대시보드로 고정해 두면, 대시보드도 못 보는 역할은 다시 이 화면으로 돌아오게 된다.
  const allowed = new Set(allowedMenus(user?.role))
  const firstAllowed = NAV_ITEMS.find((item) => allowed.has(item.key))

  // 사이드바의 로그아웃과 같은 동작. 갈 메뉴가 하나도 없는 역할도 여기서 나갈 수 있다.
  function handleLogout() {
    signOut()
    navigate('/login', { replace: true })
  }

  return (
    <section className="page not-found">
      <p className="eyebrow">403</p>
      <h1>접근 권한이 없습니다.</h1>
      <p>현재 계정의 역할로는 볼 수 없는 화면입니다.</p>

      {firstAllowed && (
        <Link className="primary-button" to={firstAllowed.to}>
          {firstAllowed.label}(으)로 이동
        </Link>
      )}

      <button type="button" onClick={handleLogout}>
        로그아웃
      </button>
    </section>
  )
}