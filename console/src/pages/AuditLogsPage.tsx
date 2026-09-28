import { PageScaffold } from '../components/ui/PageScaffold'

export function AuditLogsPage() {
  return (
    <PageScaffold
      title="감사 로그"
      description="관리자 조작 이력을 검색하고 추적할 화면입니다."
      nextStep="감사 로그 목록 mock"
    />
  )
}
