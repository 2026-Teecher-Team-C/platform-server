import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import type { DownloadDecision } from '../../lib/api/events'
import type { Severity } from '../../lib/api/dashboard'
import { authorizeMockRequest } from '../http'
import { appendMockAuditLog } from '../data/auditLogs'
import { getDashboardDataset } from '../dashboard/store'
import { filterEvents, isBlock, toListItem } from '../dashboard/aggregate'
import { invalidRequest, parsePaging } from './dashboard'

const DECISIONS: readonly DownloadDecision[] = ['RELEASED', 'BLOCKED', 'BYPASSED', 'FAIL_CLOSE']
const SEVERITIES: readonly Severity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

export const eventHandlers = [
  http.get(API_BASE_URL + '/api/admin/download-events', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const params = new URL(request.url).searchParams
    const paging = parsePaging(params)
    if ('error' in paging) return paging.error
    const { page, size } = paging

    const decisions = params.getAll('decision')
    if (decisions.some((d) => !DECISIONS.includes(d as DownloadDecision))) {
      return invalidRequest('decision 값이 올바르지 않습니다.')
    }
    const minSeverity = params.get('minSeverity')
    if (minSeverity && !SEVERITIES.includes(minSeverity as Severity)) {
      return invalidRequest('minSeverity 값이 올바르지 않습니다.')
    }
    const acknowledged = params.get('acknowledged')
    if (acknowledged && acknowledged !== 'true' && acknowledged !== 'false') {
      return invalidRequest('acknowledged는 true 또는 false여야 합니다.')
    }
    const from = params.get('from')
    const to = params.get('to')
    if ((from && Number.isNaN(new Date(from).getTime())) || (to && Number.isNaN(new Date(to).getTime()))) {
      return invalidRequest('from/to는 ISO-8601 시각이어야 합니다.')
    }

    const matched = filterEvents(getDashboardDataset(), {
      decisions: decisions as DownloadDecision[],
      agentId: params.get('agentId') ?? undefined,
      from: from ? new Date(from) : undefined,
      to: to ? new Date(to) : undefined,
      minSeverity: (minSeverity as Severity | null) ?? undefined,
      acknowledged: acknowledged ? acknowledged === 'true' : undefined,
    })

    // 서버 PageResponse와 같은 모양으로 한 페이지만 잘라 준다.
    return HttpResponse.json({
      content: matched.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: matched.length,
      totalPages: Math.ceil(matched.length / size),
    })
  }),

  http.get(
    API_BASE_URL + '/api/admin/download-events/:eventId',
    ({ request, params }) => {
      const auth = authorizeMockRequest(request)
      if (auth.error) return auth.error

      const ds = getDashboardDataset()
      const event = ds.events.find((item) => item.eventId === params.eventId)

      if (!event) return notFound()

      // 목록과 같은 값(severity, 확인 여부 등)을 쓰도록 계산 필드를 합친다.
      return HttpResponse.json({ ...event, ...toListItem(ds, event) })
    },
  ),

  http.post(
    API_BASE_URL + '/api/admin/download-events/:eventId/acknowledge',
    ({ request, params }) => {
      const auth = authorizeMockRequest(request)
      if (auth.error) return auth.error

      const ds = getDashboardDataset()
      const eventId = String(params.eventId)
      const event = ds.events.find((item) => item.eventId === eventId)
      if (!event) return notFound()

      if (!isBlock(event)) {
        return HttpResponse.json(
          { code: 'CONFLICT', message: '차단된 이벤트만 확인 처리할 수 있습니다.' },
          { status: 409 },
        )
      }

      const existing = ds.acknowledgements.get(eventId)
      if (existing) return HttpResponse.json({ eventId, ...existing })

      const record = { acknowledgedBy: auth.account.username, acknowledgedAt: new Date().toISOString() }
      ds.acknowledgements.set(eventId, record)
      appendMockAuditLog({
        action: 'DOWNLOAD_EVENT_ACKNOWLEDGE',
        dangerous: false,
        actor: { adminId: auth.account.adminId, username: auth.account.username, role: auth.account.role },
        targetType: 'DOWNLOAD_EVENT',
        targetId: eventId,
        detail: null,
        ipAddress: '127.0.0.1',
      })
      return HttpResponse.json({ eventId, ...record })
    },
  ),
]

function notFound() {
  return HttpResponse.json(
    { code: 'DOWNLOAD_EVENT_NOT_FOUND', message: '해당 eventId를 찾을 수 없습니다.' },
    { status: 404 },
  )
}
