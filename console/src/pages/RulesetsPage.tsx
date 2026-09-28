import { PageScaffold } from '../components/ui/PageScaffold'

export function RulesetsPage() {
  return (
    <PageScaffold
      title="YARA 룰셋"
      description="룰셋 버전과 활성 상태를 확인하고 이후 업로드·활성화를 연결합니다."
      nextStep="룰셋 목록 mock"
    />
  )
}
