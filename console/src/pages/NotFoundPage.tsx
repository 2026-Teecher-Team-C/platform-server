import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return (
    <section className="page not-found">
      <p className="eyebrow">404</p>
      <h1>페이지를 찾을 수 없습니다.</h1>
      <Link className="primary-button" to="/dashboard">
        대시보드로 이동
      </Link>
    </section>
  )
}
