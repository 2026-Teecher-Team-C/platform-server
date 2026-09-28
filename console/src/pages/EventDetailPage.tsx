import { useParams } from 'react-router-dom'
import { PageScaffold } from '../components/ui/PageScaffold'

export function EventDetailPage() {
  const { eventId } = useParams()

  return (
    <PageScaffold
      title="이벤트 상세"
      description={'이벤트 ID: ' + (eventId ?? '-') + ' · 파일, 에이전트, 판정 정보를 표시할 화면입니다.'}
      nextStep="API 명세 기반 상세 DTO 연결"
    />
  )
}
