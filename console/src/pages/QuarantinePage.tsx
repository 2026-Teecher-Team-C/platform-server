import { PageScaffold } from '../components/ui/PageScaffold'

export function QuarantinePage() {
  return (
    <PageScaffold
      title="격리 파일"
      description="격리된 파일 메타데이터와 상태를 조회합니다."
      nextStep="격리 목록 mock + 복원 동작 자리"
    />
  )
}
