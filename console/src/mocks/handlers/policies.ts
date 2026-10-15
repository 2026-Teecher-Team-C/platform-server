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
    const host = url.searchParams.get('host')?.toLowerCase()
    const isActive = parseBoolean(url.searchParams.get('isActive'), true)

    const items = mockBypassDomains.filter((item) => {
      if (item.isActive !== isActive) return false
      if (category && item.category !== category) return false
      if (host && !item.host.toLowerCase().includes(host)) return false
      return true
    })

    return HttpResponse.json({ items, nextCursor: null })
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
