import type { EngineStatus } from '../../lib/api/dashboard'
import { formatClock } from '../../lib/dashboard/format'

type Props = { status: EngineStatus | null; stale: boolean; now: Date }

export function EngineStatusBadge({ status, stale, now }: Props) {
  let tone: 'ok' | 'down' | 'unknown' = 'unknown'
  let text = '● 엔진 상태 확인 불가'

  if (!stale && status) {
    if (status.state === 'HEALTHY') {
      tone = 'ok'
      text = '● 탐지 엔진 정상'
    } else if (status.state === 'DOWN') {
      tone = 'down'
      text = '● 탐지 엔진 응답 없음'
      if (status.since) {
        const minutes = Math.max(0, Math.floor((now.getTime() - new Date(status.since).getTime()) / 60000))
        text += ` · ${minutes}분째`
      }
    } else {
      const last = status.lastSuccessAt ? `마지막 정상 판정 ${formatClock(status.lastSuccessAt)}` : '정상 판정 기록 없음'
      text = `● 최근 검사 없음 (${last})`
    }
  }

  return (
    <span className={`engine-badge engine-badge--${tone}`} role="status">
      {text}
    </span>
  )
}
