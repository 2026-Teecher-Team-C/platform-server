import { PageScaffold } from '../components/ui/PageScaffold'

export function PoliciesPage() {
  return (
    <PageScaffold
      title="정책"
      description="바이패스 도메인과 파일 타입 검사 정책을 관리할 화면입니다."
      nextStep="파일 타입/바이패스 mock"
    />
  )
}
