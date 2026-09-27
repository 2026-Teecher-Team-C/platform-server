import { PageScaffold } from '../components/ui/PageScaffold'

export function AgentsPage() {
  return (
    <PageScaffold
      title="PC 현황"
      description="등록된 에이전트의 상태, 버전, 마지막 하트비트를 확인합니다."
      nextStep="에이전트 목록 mock"
    />
  )
}
