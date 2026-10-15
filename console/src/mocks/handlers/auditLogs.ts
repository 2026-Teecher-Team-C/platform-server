import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import { authorizeMockRequest } from '../http'
import { mockAuditLogs } from '../data/auditLogs'

const dangerousActions = new Set([
  'ADD_WHITELIST',
  'REMOVE_BLACKLIST',
  'RESTORE_QUARANTINE',
  'ADD_BYPASS_DOMAIN',
  'UPDATE_FILE_TYPE_POLICY',
  'SET_MANUAL_VERDICT',
  'DISABLE_YARA_RULE',
])

export const auditLogHandlers = [
  http.get(API_BASE_URL + '/api/admin/audit-logs', ({ request }) => {
    const auth = authorizeMockRequest(request, 'ADMIN')
    if (auth.error) {
      if (auth.error.status === 403) {
        return HttpResponse.json(
          { code: 'FORBIDDEN', message: '감사 로그는 ADMIN만 조회할 수 있습니다.' },
          { status: 403 },
        )
      }
      return auth.error
    }

    const url = new URL(request.url)
    const actorId = url.searchParams.get('actorId')
    const actions = url.searchParams
      .get('action')
      ?.split(',')
      .map((value) => value.trim())
      .filter(Boolean)
    const targetType = url.searchParams.get('targetType')
    const from = url.searchParams.get('from')
    const to = url.searchParams.get('to')
    const dangerousOnly = url.searchParams.get('dangerousOnly') === 'true'

    const items = mockAuditLogs.filter((item) => {
      if (actorId && item.actor?.adminId !== actorId) return false
      if (actions?.length && !actions.includes(item.action)) return false
      if (targetType && item.targetType !== targetType) return false
      if (from && new Date(item.createdAt) < new Date(from)) return false
      if (to && new Date(item.createdAt) >= new Date(to)) return false
      if (dangerousOnly && !dangerousActions.has(item.action)) return false
      return true
    })

    return HttpResponse.json({ items, nextCursor: null })
  }),
]
