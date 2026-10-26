import type { DownloadDecision } from '../api/events'

// DB에는 판정이 끝나지 않아 decision이 없는 이벤트도 저장된다.
// 목록·상세·대시보드가 같은 대기 문구와 배지 색상을 사용하도록 한곳에서 정한다.
export function decisionBadge(decision: DownloadDecision | null) {
  return {
    text: decision ?? '판정 대기',
    tone: decision?.toLowerCase() ?? 'pending',
  }
}

// 서버는 DB의 빈 cache_hit을 false로 내려준다. 아직 판정 전이면 Miss로 단정하지 않는다.
export function cacheHitLabel(decision: DownloadDecision | null, cacheHit: boolean) {
  return decision === null ? '—' : cacheHit ? 'Hit' : 'Miss'
}
