import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import { authorizeMockRequest } from '../http'
import { mockDownloadEvents } from '../data/events'

function percentile99(values: number[]) {
  if (values.length === 0) return 0
  const sorted = [...values].sort((a, b) => a - b)
  const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.99) - 1)
  return sorted[index]
}

export const statsHandlers = [
  http.get(API_BASE_URL + '/api/admin/stats', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const url = new URL(request.url)
    const toParam = url.searchParams.get('to')
    const fromParam = url.searchParams.get('from')
    const to = toParam ? new Date(toParam) : new Date()
    const from = fromParam
      ? new Date(fromParam)
      : new Date(to.getTime() - 24 * 60 * 60 * 1000)

    const events = mockDownloadEvents.filter((event) => {
      const createdAt = new Date(event.createdAt)
      return createdAt >= from && createdAt < to
    })

    // "허용" = RELEASED(정상 통과) + BYPASSED(바이패스 도메인), "차단" = BLOCKED(탐지) + FAIL_CLOSE
    // (분석 실패로 인한 안전차단) — 둘 다 파일이 사용자에게 안 넘어갔다는 뜻이라 같은 그룹.
    const blockedCount = events.filter(
      (event) => event.decision === 'BLOCKED' || event.decision === 'FAIL_CLOSE',
    ).length
    const allowedCount = events.filter(
      (event) => event.decision === 'RELEASED' || event.decision === 'BYPASSED',
    ).length
    const cacheHits = events.filter((event) => event.cacheHit).length
    const totalBytesUploaded = events.reduce(
      (sum, event) => sum + event.bytesUploaded,
      0,
    )
    const totalFileBytes = events.reduce(
      (sum, event) => sum + (event.fileSize ?? 0),
      0,
    )
    const holdDurations = events
      .map((event) => event.holdDurationMs)
      .filter((value): value is number => value !== null)

    return HttpResponse.json({
      period: {
        from: from.toISOString(),
        to: to.toISOString(),
      },
      totalEvents: events.length,
      blockedCount,
      allowedCount,
      cacheHitRate: events.length ? cacheHits / events.length : 0,
      holdDurationP99Ms: percentile99(holdDurations),
      totalBytesUploaded,
      bandwidthSavedRatio:
        totalFileBytes > 0
          ? Math.max(0, (totalFileBytes - totalBytesUploaded) / totalFileBytes)
          : 0,
    })
  }),
]
