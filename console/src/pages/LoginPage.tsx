import { Link } from 'react-router-dom'

export function LoginPage() {
  return (
    <main className="login-page">
      <section className="login-card">
        <p className="eyebrow">Teecher Team C</p>
        <h1>관리 콘솔</h1>
        <p>
          실제 인증 연결은 Sprint 2 범위입니다. Sprint 1에서는 화면 개발을 위한 진입점만 둡니다.
        </p>
        <Link className="primary-button" to="/dashboard">
          개발용 콘솔 진입
        </Link>
      </section>
    </main>
  )
}
