import { PageScaffold } from '../components/ui/PageScaffold'

export function EventsPage() {
  return (
    <PageScaffold
      title="다운로드 이벤트"
      description="다운로드 판정 결과와 처리 상태를 조회하고 상세 화면으로 이동합니다."
      nextStep="이벤트 목록 mock + 상세 링크"
    />
  )
}
