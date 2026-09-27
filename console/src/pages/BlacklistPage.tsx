import { PageScaffold } from '../components/ui/PageScaffold'

export function BlacklistPage() {
  return (
    <PageScaffold
      title="블랙리스트"
      description="악성 확정 SHA-256 목록을 조회하고 관리할 화면입니다."
      nextStep="목록 mock과 등록/수정 UI 자리"
    />
  )
}
