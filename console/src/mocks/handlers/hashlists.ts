import { http, HttpResponse } from 'msw'
import type { BlacklistSeverity, CreateBlacklistRequest, CreateWhitelistRequest } from '../../lib/api/hashlists'
import { API_BASE_URL } from '../../lib/api/client'
import { authorizeMockRequest, isSha256 } from '../http'
import { appendMockAuditLog } from '../data/auditLogs'
import { mockBlacklist, mockWhitelist } from '../data/hashlists'

const MOCK_MANUAL_BLACKLIST_SEVERITY: BlacklistSeverity = 'HIGH'

function parseBoolean(value: string | null, fallback: boolean) {
  if (value === null) return fallback
  return value === 'true'
}

export const hashlistHandlers = [
  http.get(API_BASE_URL + '/api/admin/whitelist', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const url = new URL(request.url)
    const hashValue = url.searchParams.get('hashValue')?.toLowerCase()
    const origin = url.searchParams.get('origin')
    const isActive = parseBoolean(url.searchParams.get('isActive'), true)
    const includeExpired = parseBoolean(url.searchParams.get('includeExpired'), false)
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    const now = Date.now()

    // 서버와 같이 hashValue는 앞부분 일치로 찾는다.
    const matched = mockWhitelist.filter((item) => {
      if (item.isActive !== isActive) return false
      if (hashValue && !item.hashValue.startsWith(hashValue)) return false
      if (origin && item.origin !== origin) return false
      if (!includeExpired && item.expiresAt && new Date(item.expiresAt).getTime() <= now) {
        return false
      }
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

  http.post(API_BASE_URL + '/api/admin/whitelist', async ({ request }) => {
    const auth = authorizeMockRequest(request, 'ANALYST')
    if (auth.error) return auth.error
    const account = auth.account!

    const body = (await request.json().catch(() => null)) as CreateWhitelistRequest | null

    if (!body || !isSha256(body.sha256) || !body.reason?.trim()) {
      return HttpResponse.json(
        { code: 'VALIDATION_ERROR', message: '64자리 SHA-256과 reason이 필요합니다.' },
        { status: 400 },
      )
    }

    const hashValue = body.sha256.toLowerCase()
    const conflict =
      mockBlacklist.some((item) => item.hashValue === hashValue && item.isActive) ||
      mockWhitelist.some((item) => item.hashValue === hashValue && item.isActive)

    if (conflict) {
      return HttpResponse.json(
        { code: 'HASH_LIST_CONFLICT', message: '이미 활성 해시 목록에 등록된 SHA-256입니다.' },
        { status: 409 },
      )
    }

    const createdAt = new Date().toISOString()
    const item = {
      hashType: 'SHA256' as const,
      hashValue,
      reason: body.reason.trim(),
      origin: 'PREREGISTERED' as const,
      originQuarantineId: null,
      addedBy: {
        adminId: account.adminId,
        username: account.username,
      },
      isActive: true,
      expiresAt: body.expiresAt ?? null,
      createdAt,
    }

    mockWhitelist.unshift(item)
    appendMockAuditLog({
      action: 'ADD_WHITELIST',
      dangerous: true,
      actor: {
        adminId: account.adminId,
        username: account.username,
        role: account.role,
      },
      targetType: 'WHITELIST',
      targetId: 'SHA256:' + hashValue,
      detail: { after: { reason: item.reason } },
      ipAddress: '127.0.0.1',
    })

    return HttpResponse.json(item, { status: 201 })
  }),

  http.patch(
    API_BASE_URL + '/api/admin/whitelist/:sha256',
    async ({ request, params }) => {
      const auth = authorizeMockRequest(request, 'ANALYST')
      if (auth.error) return auth.error

      const sha256 = String(params.sha256 ?? '').toLowerCase()
      const body = (await request.json().catch(() => null)) as { reason?: string } | null

      if (!isSha256(sha256) || !body?.reason?.trim()) {
        return HttpResponse.json(
          { code: 'VALIDATION_ERROR', message: '유효한 SHA-256과 reason이 필요합니다.' },
          { status: 400 },
        )
      }

      const item = mockWhitelist.find(
        (entry) => entry.hashValue === sha256 && entry.isActive,
      )

      if (!item) {
        return HttpResponse.json(
          { code: 'WHITELIST_NOT_FOUND', message: '활성 화이트리스트 항목을 찾을 수 없습니다.' },
          { status: 404 },
        )
      }

      item.reason = body.reason.trim()
      item.updatedAt = new Date().toISOString()
      return HttpResponse.json(item)
    },
  ),

  http.delete(
    API_BASE_URL + '/api/admin/whitelist/:sha256',
    ({ request, params }) => {
      const auth = authorizeMockRequest(request, 'ANALYST')
      if (auth.error) return auth.error

      const sha256 = String(params.sha256 ?? '').toLowerCase()
      if (!isSha256(sha256)) {
        return HttpResponse.json(
          { code: 'VALIDATION_ERROR', message: '유효한 SHA-256이 필요합니다.' },
          { status: 400 },
        )
      }

      const item = mockWhitelist.find(
        (entry) => entry.hashValue === sha256 && entry.isActive,
      )

      if (!item) {
        return HttpResponse.json(
          { code: 'WHITELIST_NOT_FOUND', message: '활성 화이트리스트 항목을 찾을 수 없습니다.' },
          { status: 404 },
        )
      }

      item.isActive = false
      return new HttpResponse(null, { status: 204 })
    },
  ),

  http.get(API_BASE_URL + '/api/admin/blacklist', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const url = new URL(request.url)
    const hashValue = url.searchParams.get('hashValue')?.trim().toLowerCase()
    const source = url.searchParams.get('source')?.trim()
    const severity = url.searchParams.get('severity')?.trim()
    const active = url.searchParams.get('isActive')
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    // 서버와 같은 입력 범위를 검증해야 mock에서만 성공하는 잘못된 요청을 발견할 수 있다.
    if ((hashValue && !/^[0-9a-f]{1,64}$/.test(hashValue)) ||
      (severity && !['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].includes(severity)) ||
      (source && !['MANUAL', 'IMPORT'].includes(source)) ||
      (active !== null && !['true', 'false'].includes(active)) ||
      !Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 200) {
      return HttpResponse.json({ code: 'INVALID_INPUT', message: '블랙리스트 조회 조건을 확인해 주세요.' }, { status: 400 })
    }
    const items = mockBlacklist.filter((item) =>
      item.isActive === (active !== 'false') &&
      (!hashValue || item.hashValue.startsWith(hashValue)) &&
      (!severity || item.severity === severity) && (!source || item.source === source),
    ).sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || b.hashValue.localeCompare(a.hashValue))
    return HttpResponse.json({ content: items.slice(page * size, (page + 1) * size), page, size, totalElements: items.length, totalPages: Math.ceil(items.length / size) })
  }),

  http.post(API_BASE_URL + '/api/admin/blacklist', async ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error
    const account = auth.account!
    const body = await request.json().catch(() => null) as CreateBlacklistRequest | null
    if (!body || !isSha256(body.sha256) || typeof body.reason !== 'string' || !body.reason.trim()) {
      return HttpResponse.json({ code: 'INVALID_INPUT', message: '64자리 SHA-256과 사유가 필요합니다.' }, { status: 400 })
    }
    const hashValue = body.sha256.toLowerCase()
    const conflict = mockWhitelist.some((item) => item.hashValue === hashValue && item.isActive && (!item.expiresAt || Date.parse(item.expiresAt) > Date.now())) ||
      mockBlacklist.some((item) => item.hashValue === hashValue && item.isActive)
    if (conflict) return HttpResponse.json({ code: 'CONFLICT', message: '이미 유효한 해시 목록에 등록된 SHA-256입니다.' }, { status: 409 })
    // 실제 서버는 해제된 행을 재활성화하고 최초 등록 시각을 유지한다. 심각도와 출처도 서버 기준이다.
    const existing = mockBlacklist.find((item) => item.hashValue === hashValue)
    const item = {
      hashType: 'SHA256' as const, hashValue, reason: body.reason.trim(), severity: MOCK_MANUAL_BLACKLIST_SEVERITY,
      source: 'MANUAL' as const, addedBy: { adminId: account.adminId, username: account.username },
      isActive: true, createdAt: existing?.createdAt ?? new Date().toISOString(), deactivatedAt: null,
    }
    if (existing) Object.assign(existing, item)
    else mockBlacklist.unshift(item)
    appendMockAuditLog({ action: 'ADD_BLACKLIST', dangerous: false, actor: { adminId: account.adminId, username: account.username, role: account.role }, targetType: 'BLACKLIST', targetId: 'SHA256:' + hashValue, detail: null, ipAddress: '127.0.0.1' })
    return HttpResponse.json(item, { status: 201 })
  }),

  http.patch(API_BASE_URL + '/api/admin/blacklist/:sha256', async ({ request, params }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error
    const sha256 = String(params.sha256 ?? '').toLowerCase()
    const body = await request.json().catch(() => null) as { reason?: string } | null
    if (!isSha256(sha256) || typeof body?.reason !== 'string' || !body.reason.trim()) {
      return HttpResponse.json({ code: 'INVALID_INPUT', message: '유효한 SHA-256과 사유가 필요합니다.' }, { status: 400 })
    }
    const item = mockBlacklist.find((entry) => entry.hashValue === sha256 && entry.isActive)
    if (!item) return HttpResponse.json({ code: 'NOT_FOUND', message: '활성 블랙리스트 항목을 찾을 수 없습니다.' }, { status: 404 })
    // 실제 수정 API와 같이 이전/이후 사유를 감사 로그 상세에 남긴다.
    const previousReason = item.reason
    item.reason = body.reason.trim()
    appendMockAuditLog({ action: 'UPDATE_BLACKLIST', dangerous: false, actor: { adminId: auth.account!.adminId, username: auth.account!.username, role: auth.account!.role }, targetType: 'BLACKLIST', targetId: 'SHA256:' + sha256, detail: { previousReason, newReason: item.reason }, ipAddress: '127.0.0.1' })
    return HttpResponse.json(item)
  }),

  http.delete(API_BASE_URL + '/api/admin/blacklist/:sha256', ({ request, params }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error
    const sha256 = String(params.sha256 ?? '').toLowerCase()
    if (!isSha256(sha256)) return HttpResponse.json({ code: 'INVALID_INPUT', message: '유효한 SHA-256이 필요합니다.' }, { status: 400 })
    const item = mockBlacklist.find((entry) => entry.hashValue === sha256 && entry.isActive)
    if (!item) return HttpResponse.json({ code: 'NOT_FOUND', message: '활성 블랙리스트 항목을 찾을 수 없습니다.' }, { status: 404 })
    item.isActive = false
    item.deactivatedAt = new Date().toISOString()
    appendMockAuditLog({ action: 'REMOVE_BLACKLIST', dangerous: true, actor: { adminId: auth.account!.adminId, username: auth.account!.username, role: auth.account!.role }, targetType: 'BLACKLIST', targetId: 'SHA256:' + sha256, detail: null, ipAddress: '127.0.0.1' })
    return new HttpResponse(null, { status: 204 })
  }),
]
