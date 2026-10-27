import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import { authorizeMockRequest } from '../http'
import {
  mockBypassDomains,
  mockFileTypePolicies,
} from '../data/policies'

function parseBoolean(value: string | null, fallback: boolean) {
  if (value === null) return fallback
  return value === 'true'
}

export const policyHandlers = [
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
