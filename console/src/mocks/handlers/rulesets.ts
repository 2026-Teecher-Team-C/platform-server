import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import type { PageResponse } from '../../lib/api/types'
import { authorizeMockRequest } from '../http'
import { mockRulesByVersion, mockRulesets } from '../data/rulesets'

function onePage<T>(content: T[], page = 0, size = content.length): PageResponse<T> {
  return {
    content,
    page,
    size,
    totalElements: content.length,
    totalPages: content.length ? Math.ceil(content.length / Math.max(size, 1)) : 0,
  }
}

function notFound() {
  return HttpResponse.json({ code: 'NOT_FOUND', message: '룰셋을 찾을 수 없습니다.' }, { status: 404 })
}

function withDisabledCount(version: number) {
  const item = mockRulesets.find((ruleset) => ruleset.version === version)
  if (!item) return null
  item.disabledRuleCount = (mockRulesByVersion[version] ?? []).filter((rule) => !rule.enabled).length
  return item
}

export const rulesetHandlers = [
  http.get(API_BASE_URL + '/api/admin/rulesets', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    const sorted = [...mockRulesets].sort((a, b) => b.version - a.version)
    const response = onePage(sorted.slice(page * size, (page + 1) * size), page, size)
    return HttpResponse.json({ ...response, totalElements: sorted.length, totalPages: Math.ceil(sorted.length / size) })
  }),

  http.get(API_BASE_URL + '/api/admin/rulesets/:version/rules', ({ request, params }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const rules = mockRulesByVersion[Number(params.version)]
    if (!rules) return notFound()
    return HttpResponse.json(onePage([...rules].sort((a, b) => a.ruleName.localeCompare(b.ruleName))))
  }),

  http.post(API_BASE_URL + '/api/admin/rulesets', async ({ request }) => {
    const auth = authorizeMockRequest(request, 'ADMIN')
    if (auth.error) return auth.error

    const form = await request.formData()
    const file = form.get('file')
    if (!(file instanceof File)) {
      return HttpResponse.json({ code: 'INVALID_REQUEST', message: '파일이 필요합니다.' }, { status: 400 })
    }
    if (file.name.includes('error')) {
      return HttpResponse.json({ code: 'RULESET_COMPILE_ERROR', message: 'line 3: syntax error' }, { status: 422 })
    }

    const version = Math.max(...mockRulesets.map((ruleset) => ruleset.version)) + 1
    mockRulesByVersion[version] = [
      { ruleId: 'v' + version + '-uploaded', ruleName: 'Uploaded_Rule', severity: 'MEDIUM', enabled: true },
    ]
    const created = {
      version, ruleCount: 1, disabledRuleCount: 0, isActive: false,
      createdAt: new Date().toISOString(), activatedAt: null,
    }
    mockRulesets.push(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.post(API_BASE_URL + '/api/admin/rulesets/:version/activate', ({ request, params }) => {
    const auth = authorizeMockRequest(request, 'ADMIN')
    if (auth.error) return auth.error

    const target = mockRulesets.find((ruleset) => ruleset.version === Number(params.version))
    if (!target) return notFound()
    if (!target.isActive) {
      for (const ruleset of mockRulesets) ruleset.isActive = false
      target.isActive = true
      target.activatedAt = new Date().toISOString()
    }
    return HttpResponse.json(target)
  }),

  http.patch(API_BASE_URL + '/api/admin/rulesets/:version/rules/:ruleId', async ({ request, params }) => {
    const auth = authorizeMockRequest(request, 'ADMIN')
    if (auth.error) return auth.error

    const version = Number(params.version)
    const rule = mockRulesByVersion[version]?.find((candidate) => candidate.ruleId === params.ruleId)
    if (!rule) return notFound()
    const body = await request.json() as { enabled: boolean }
    rule.enabled = body.enabled
    withDisabledCount(version)
    return HttpResponse.json(rule)
  }),
]
