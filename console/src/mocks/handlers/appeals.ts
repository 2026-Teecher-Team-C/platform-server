import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import type { AdminAppealDetail } from '../../lib/api/adminAppeals'
import { authorizeMockRequest } from '../http'
import { mockAppeals } from '../data/appeals'
import { appendMockAuditLog } from '../data/auditLogs'
import { mockBlacklist, mockWhitelist } from '../data/hashlists'

const NOTE_MAX = 1000

function detailOf(appeal: AdminAppealDetail): AdminAppealDetail {
  // 서버처럼 조회 시점의 활성 블랙리스트 여부를 담는다.
  return { ...appeal, blacklisted: mockBlacklist.some((entry) => entry.hashValue === appeal.sha256 && entry.isActive) }
}

function listItemOf(appeal: AdminAppealDetail) {
  const { appealId, status, createdAt, decidedAt, hostname, filename, sha256, evidenceKind, threat, reasonPreview } = appeal
  return { appealId, status, createdAt, decidedAt, hostname, filename, sha256, evidenceKind, threat, reasonPreview }
}

function notFound() {
  return HttpResponse.json({ code: 'NOT_FOUND', message: 'appeal not found' }, { status: 404 })
}

// 서버 DecideAppealUseCase와 같이 앞뒤 공백을 빼고 1~1000자, NUL 금지. 승인은 비어도 된다.
async function readNote(request: Request, required: boolean): Promise<{ note: string | null } | { error: Response }> {
  let body: { note?: unknown } | null
  try {
    const text = await request.text()
    body = text.trim() ? JSON.parse(text) : null
  } catch {
    return { error: HttpResponse.json({ code: 'INVALID_INPUT', message: 'note를 확인해 주세요.' }, { status: 400 }) }
  }
  const raw = body && typeof body === 'object' && typeof body.note === 'string' ? body.note.trim() : ''
  if ((required && !raw) || [...raw].length > NOTE_MAX || raw.includes('\u0000')) {
    return { error: HttpResponse.json({ code: 'INVALID_INPUT', message: 'note는 1~1000자여야 합니다.' }, { status: 400 }) }
  }
  return { note: raw || null }
}

export const appealHandlers = [
  http.get(API_BASE_URL + '/api/admin/appeals', ({ request }) => {
    const auth = authorizeMockRequest(request, 'ANALYST')
    if (auth.error) return auth.error
    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    const matched = mockAppeals
      .filter((appeal) => !status || appeal.status === status)
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    return HttpResponse.json({
      content: matched.slice(page * size, (page + 1) * size).map(listItemOf),
      page,
      size,
      totalElements: matched.length,
      totalPages: Math.ceil(matched.length / size),
    })
  }),

  http.get(API_BASE_URL + '/api/admin/appeals/:appealId', ({ request, params }) => {
    const auth = authorizeMockRequest(request, 'ANALYST')
    if (auth.error) return auth.error
    const appeal = mockAppeals.find((entry) => entry.appealId === params.appealId)
    return appeal ? HttpResponse.json(detailOf(appeal)) : notFound()
  }),

  http.post(API_BASE_URL + '/api/admin/appeals/:appealId/approve', async ({ request, params }) => {
    const auth = authorizeMockRequest(request, 'ADMIN')
    if (auth.error) return auth.error
    const account = auth.account!
    const read = await readNote(request, false)
    if ('error' in read) return read.error
    const appeal = mockAppeals.find((entry) => entry.appealId === params.appealId)
    if (!appeal) return notFound()
    if (appeal.status !== 'PENDING') {
      return HttpResponse.json({ code: 'CONFLICT', message: 'appeal already decided' }, { status: 409 })
    }
    const now = new Date().toISOString()
    const actor = { adminId: account.adminId, username: account.username, role: account.role }
    Object.assign(appeal, { status: 'APPROVED', decidedAt: now, decidedBy: account.username, decisionNote: read.note })

    // 격리 복원과 같이 같은 해시의 활성 블랙리스트를 해제하고, 유효한 화이트리스트가 없을 때만 등록·재활성화한다.
    const blocked = mockBlacklist.find((entry) => entry.hashValue === appeal.sha256 && entry.isActive)
    if (blocked) {
      blocked.isActive = false
      blocked.deactivatedAt = now
      appendMockAuditLog({ action: 'REMOVE_BLACKLIST', dangerous: true, actor, targetType: 'BLACKLIST', targetId: 'SHA256:' + appeal.sha256, detail: { reason: '소명 승인으로 자동 해제', appealId: appeal.appealId }, ipAddress: '127.0.0.1' })
    }
    const existing = mockWhitelist.find((entry) => entry.hashValue === appeal.sha256)
    const effective = existing?.isActive && (!existing.expiresAt || Date.parse(existing.expiresAt) > Date.now())
    if (!effective) {
      const whitelistItem = {
        hashType: 'SHA256',
        hashValue: appeal.sha256,
        reason: read.note ?? '소명 승인',
        origin: 'APPEAL',
        originQuarantineId: null,
        originAppealId: appeal.appealId,
        addedBy: { adminId: account.adminId, username: account.username },
        isActive: true,
        expiresAt: null,
        createdAt: existing?.createdAt ?? now,
      } as const
      if (existing) Object.assign(existing, whitelistItem)
      else mockWhitelist.unshift(whitelistItem)
    }
    appendMockAuditLog({ action: 'APPROVE_APPEAL', dangerous: true, actor, targetType: 'APPEAL', targetId: appeal.appealId, detail: { sha256: appeal.sha256, note: read.note }, ipAddress: '127.0.0.1' })
    return HttpResponse.json(detailOf(appeal))
  }),

  http.post(API_BASE_URL + '/api/admin/appeals/:appealId/reject', async ({ request, params }) => {
    const auth = authorizeMockRequest(request, 'ADMIN')
    if (auth.error) return auth.error
    const account = auth.account!
    const read = await readNote(request, true)
    if ('error' in read) return read.error
    const appeal = mockAppeals.find((entry) => entry.appealId === params.appealId)
    if (!appeal) return notFound()
    if (appeal.status !== 'PENDING') {
      return HttpResponse.json({ code: 'CONFLICT', message: 'appeal already decided' }, { status: 409 })
    }
    Object.assign(appeal, { status: 'REJECTED', decidedAt: new Date().toISOString(), decidedBy: account.username, decisionNote: read.note })
    appendMockAuditLog({ action: 'REJECT_APPEAL', dangerous: false, actor: { adminId: account.adminId, username: account.username, role: account.role }, targetType: 'APPEAL', targetId: appeal.appealId, detail: { sha256: appeal.sha256, note: read.note }, ipAddress: '127.0.0.1' })
    return HttpResponse.json(detailOf(appeal))
  }),
]
