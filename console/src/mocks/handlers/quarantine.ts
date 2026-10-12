import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import { authorizeMockRequest } from '../http'
import { appendMockAuditLog } from '../data/auditLogs'
import { mockDownloadEvents } from '../data/events'
import { mockWhitelist } from '../data/hashlists'
import { mockQuarantine } from '../data/quarantine'

export const quarantineHandlers = [
  http.get(API_BASE_URL + '/api/admin/quarantine', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    const agentId = url.searchParams.get('agentId')

    const items = mockQuarantine.filter((item) => {
      if (status && item.status !== status) return false
      if (agentId && item.agentId !== agentId) return false
      return true
    })

    return HttpResponse.json({ items, nextCursor: null })
  }),

  http.get(
    API_BASE_URL + '/api/admin/quarantine/:quarantineId',
    ({ request, params }) => {
      const auth = authorizeMockRequest(request)
      if (auth.error) return auth.error

      const item = mockQuarantine.find(
        (entry) => entry.quarantineId === params.quarantineId,
      )

      if (!item) {
        return HttpResponse.json(
          { code: 'QUARANTINE_NOT_FOUND', message: '해당 quarantineId를 찾을 수 없습니다.' },
          { status: 404 },
        )
      }

      const event = mockDownloadEvents.find(
        (entry) => entry.eventId === item.eventId,
      )

      return HttpResponse.json({
        ...item,
        downloadEvent: event
          ? {
              eventId: event.eventId,
              requestHost: event.requestHost,
              url: event.url,
            }
          : undefined,
        agent: {
          agentId: item.agentId,
          hostname: item.hostname,
        },
      })
    },
  ),

  http.post(
    API_BASE_URL + '/api/admin/quarantine/:quarantineId/restore',
    ({ request, params }) => {
      const auth = authorizeMockRequest(request, 'ANALYST')
      if (auth.error) return auth.error
      const account = auth.account!

      const item = mockQuarantine.find(
        (entry) => entry.quarantineId === params.quarantineId,
      )

      if (!item) {
        return HttpResponse.json(
          { code: 'QUARANTINE_NOT_FOUND', message: '해당 quarantineId를 찾을 수 없습니다.' },
          { status: 404 },
        )
      }

      if (item.status === 'RESTORED') {
        return HttpResponse.json(
          { code: 'ALREADY_RESTORED', message: '이미 복원된 격리 파일입니다.' },
          { status: 409 },
        )
      }

      const restoredAt = new Date().toISOString()
      item.status = 'RESTORED'
      item.restoredAt = restoredAt

      const existing = mockWhitelist.find(
        (entry) => entry.hashValue === item.sha256 && entry.isActive,
      )
      if (!existing) {
        mockWhitelist.unshift({
          hashType: 'SHA256',
          hashValue: item.sha256,
          reason: '오탐 복원으로 자동 등록',
          origin: 'RESTORED',
          originQuarantineId: item.quarantineId,
          addedBy: {
            adminId: account.adminId,
            username: account.username,
          },
          isActive: true,
          expiresAt: null,
          createdAt: restoredAt,
        })
      }

      appendMockAuditLog({
        action: 'RESTORE_QUARANTINE',
        dangerous: true,
        actor: {
          adminId: account.adminId,
          username: account.username,
          role: account.role,
        },
        targetType: 'QUARANTINE',
        targetId: item.quarantineId,
        detail: { after: { status: 'RESTORED', sha256: item.sha256 } },
        ipAddress: '127.0.0.1',
      })

      return HttpResponse.json({
        quarantineId: item.quarantineId,
        sha256: item.sha256,
        status: 'RESTORED',
        whitelisted: true,
        restoredAt,
      })
    },
  ),
]
