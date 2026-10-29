import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import { validateBypassInput } from '../../lib/bypass'
import { authorizeMockRequest } from '../http'
import {
  mockBypassDomains,
  mockFileTypePolicies,
} from '../data/policies'

function parseBoolean(value: string | null, fallback: boolean) {
  if (value === null) return fallback
  return value === 'true'
}

function createBypassId() {
  // 사설 HTTP 주소에는 randomUUID가 없을 수 있다. getRandomValues로 UUID v4 형식을 직접 만든다.
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-')
}

export const policyHandlers = [
  http.post(API_BASE_URL + '/api/admin/bypass-domains', async ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error
    let input
    try {
      const body = await request.json() as Record<string, unknown> | null
      if (!body || typeof body.host !== 'string' || typeof body.category !== 'string' || typeof body.reason !== 'string') throw new Error('필수 입력을 확인해 주세요.')
      input = validateBypassInput({ host: body.host, category: body.category, reason: body.reason })
    } catch (error) {
      return HttpResponse.json({ code: 'INVALID_INPUT', message: error instanceof Error ? error.message : '입력을 확인해 주세요.' }, { status: 400 })
    }
    const existing = mockBypassDomains.find((item) => item.host === input.host)
    if (existing?.isActive) return HttpResponse.json({ code: 'CONFLICT', message: '이미 바이패스로 등록된 host입니다.' }, { status: 409 })
    // 재등록은 기존 ID와 최초 등록 시각을 유지하고 분류, 사유를 새 입력으로 덮어쓴다.
    if (existing) {
      Object.assign(existing, input, { isActive: true })
      return HttpResponse.json(existing, { status: 201 })
    }
    const item = { ...input, bypassId: createBypassId(), isActive: true, createdAt: new Date().toISOString() }
    mockBypassDomains.push(item)
    return HttpResponse.json(item, { status: 201 })
  }),
  http.delete(API_BASE_URL + '/api/admin/bypass-domains/:bypassId', ({ request, params }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error
    const id = String(params.bypassId)
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
      return HttpResponse.json({ code: 'INVALID_INPUT', message: 'bypassId must be a valid UUID' }, { status: 400 })
    }
    const item = mockBypassDomains.find((row) => row.bypassId === id.toLowerCase() && row.isActive)
    if (!item) return HttpResponse.json({ code: 'NOT_FOUND', message: '적용 중인 바이패스 도메인을 찾을 수 없습니다.' }, { status: 404 })
    // 행과 사유는 남긴다. 이후 같은 host를 POST하면 다시 적용할 수 있다.
    item.isActive = false
    return new HttpResponse(null, { status: 204 })
  }),
  http.get(API_BASE_URL + '/api/admin/bypass-domains', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const url = new URL(request.url)
    const category = url.searchParams.get('category')
    if (category !== null && !['PINNED', 'SECURITY_UPDATE'].includes(category)) {
      return HttpResponse.json({ code: 'INVALID_INPUT', message: '카테고리를 확인해 주세요.' }, { status: 400 })
    }
    const host = url.searchParams.get('host')?.trim().toLowerCase()
    const isActive = parseBoolean(url.searchParams.get('isActive'), true)

    const items = mockBypassDomains.filter((item) => {
      if (item.isActive !== isActive) return false
      if (category && item.category !== category) return false
      if (host && !item.host.toLowerCase().includes(host)) return false
      return true
    })

    // 실제 API처럼 최신 등록 순서로 정렬한 뒤 page/size로 자른다.
    items.sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.bypassId.localeCompare(a.bypassId))
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 200) {
      return HttpResponse.json({ code: 'INVALID_INPUT', message: '페이지와 크기를 확인해 주세요.' }, { status: 400 })
    }
    return HttpResponse.json({
      content: items.slice(page * size, (page + 1) * size),
      page, size, totalElements: items.length, totalPages: Math.ceil(items.length / size),
    })
  }),

  http.get(API_BASE_URL + '/api/admin/file-type-policies', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const url = new URL(request.url)
    const fileType = url.searchParams.get('fileType')?.toUpperCase()
    const isActive = parseBoolean(url.searchParams.get('isActive'), true)

    const items = mockFileTypePolicies.filter((item) => {
      if (item.isActive !== isActive) return false
      if (fileType && item.fileType !== fileType) return false
      return true
    })

    return HttpResponse.json({ items })
  }),
]
