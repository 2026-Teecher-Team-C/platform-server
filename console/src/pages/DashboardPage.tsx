import { PageScaffold } from '../components/ui/PageScaffold'

export function DashboardPage() {
  return (
    <PageScaffold
      title="대시보드"
      description="검사 p99, 캐시 히트율, 전송량 절감률, 검사 실패율을 한눈에 보여줄 화면입니다."
      nextStep="메트릭 계약과 mock summary 연결"
    />
  )
}
