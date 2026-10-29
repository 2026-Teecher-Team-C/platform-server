import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import { authorizeMockRequest } from '../http'
import { appendMockAuditLog } from '../data/auditLogs'
import { mockDownloadEvents } from '../data/events'
import { mockBlacklist, mockWhitelist } from '../data/hashlists'
import { mockQuarantine } from '../data/quarantine'

export const quarantineHandlers = [
  http.get(API_BASE_URL + '/api/admin/quarantine', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    const agentId = url.searchParams.get('agentId')
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)

    const matched = mockQuarantine.filter((item) => {
      if (status && item.status !== status) return false
      if (agentId && item.agentId !== agentId) return false
      return true
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
    async ({ request, params }) => {
      const auth = authorizeMockRequest(request)
      if (auth.error) return auth.error
      const account = auth.account!

      // 본문은 선택이다. 빈 사유는 서버 기본값을 쓰고 잘못된 JSON은 변경 전에 거절한다.
      let body: { reason?: unknown } | null
      try {
        const text = await request.text()
        body = text.trim() ? JSON.parse(text) : null
      } catch {
        return HttpResponse.json({ code: 'INVALID_INPUT', message: '복원 사유를 확인해 주세요.' }, { status: 400 })
      }
      if (body !== null && (typeof body !== 'object' || Array.isArray(body) || (body.reason != null && typeof body.reason !== 'string'))) {
        return HttpResponse.json({ code: 'INVALID_INPUT', message: '복원 사유를 확인해 주세요.' }, { status: 400 })
      }
      const reason = typeof body?.reason === 'string' && body.reason.trim() ? body.reason : '오탐 복원'

      const item = mockQuarantine.find(
        (entry) => entry.quarantineId === params.quarantineId,
      )

      if (!item) {
        return HttpResponse.json(
          { code: 'NOT_FOUND', message: '해당 quarantineId를 찾을 수 없습니다.' },
          { status: 404 },
        )
      }

      if (item.status !== 'QUARANTINED') {
        return HttpResponse.json(
          { code: 'CONFLICT', message: '복원할 수 없는 상태의 격리 파일입니다.' },
          { status: 409 },
        )
      }

      const restoredAt = new Date().toISOString()
      item.status = 'RESTORED'
      item.restoredAt = restoredAt

      const existing = mockWhitelist.find(
        (entry) => entry.hashValue === item.sha256,
      )
      const effective = existing?.isActive && (!existing.expiresAt || Date.parse(existing.expiresAt) > Date.now())
      if (!effective) {
        const whitelistItem = {
          hashType: 'SHA256',
          hashValue: item.sha256,
          reason,
          origin: 'RESTORED',
          originQuarantineId: item.quarantineId,
          addedBy: {
            adminId: account.adminId,
            username: account.username,
          },
          isActive: true,
          expiresAt: null,
          createdAt: existing?.createdAt ?? restoredAt,
        } as const
        // 만료/비활성 행은 PK와 최초 등록 시각을 유지한다. 이미 유효한 행은 변경하지 않는다.
        if (existing) Object.assign(existing, whitelistItem)
        else mockWhitelist.unshift(whitelistItem)
        appendMockAuditLog({ action: 'ADD_WHITELIST', dangerous: false, actor: { adminId: account.adminId, username: account.username, role: account.role }, targetType: 'WHITELIST', targetId: 'SHA256:' + item.sha256, detail: { reason, source: 'QUARANTINE_RESTORE', quarantineId: item.quarantineId }, ipAddress: '127.0.0.1' })
      }

      // 같은 해시의 활성 차단만 해제한다. 다른 항목은 건드리지 않는다.
      const blocked = mockBlacklist.find((entry) => entry.hashValue === item.sha256 && entry.isActive)
      if (blocked) {
        blocked.isActive = false
        blocked.deactivatedAt = restoredAt
        appendMockAuditLog({ action: 'REMOVE_BLACKLIST', dangerous: true, actor: { adminId: account.adminId, username: account.username, role: account.role }, targetType: 'BLACKLIST', targetId: 'SHA256:' + item.sha256, detail: { reason: '격리 파일 오탐 복원으로 자동 해제', quarantineId: item.quarantineId }, ipAddress: '127.0.0.1' })
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
        detail: { sha256: item.sha256 },
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
