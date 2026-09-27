type PageScaffoldProps = {
  title: string
  description: string
  nextStep: string
}

export function PageScaffold({ title, description, nextStep }: PageScaffoldProps) {
  return (
    <section className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Sprint 1 wireframe</p>
          <h1>{title}</h1>
          <p>{description}</p>
        </div>
        <span className="phase-badge">Mock</span>
      </div>

      <div className="placeholder-panel">
        <div className="placeholder-grid">
          <div className="placeholder-card">
            <span>현재 단계</span>
            <strong>라우팅 · 레이아웃 구축</strong>
          </div>
          <div className="placeholder-card">
            <span>다음 단계</span>
            <strong>{nextStep}</strong>
          </div>
        </div>
        <p className="placeholder-note">
          실제 REST/SSE 연동 전까지 API 명세 기반 mock 데이터로 화면을 개발합니다.
        </p>
      </div>
    </section>
  )
}
