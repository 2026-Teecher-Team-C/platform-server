import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import type { StackBy } from '../../lib/api/dashboard'
import { authorizeMockRequest } from '../http'
import { getDashboardDataset } from '../dashboard/store'
import {
  computeDailyBlocks,
  computeEngineStatus,
  computeSummary,
  computeThreats,
  computeTopSources,
  listUnacknowledged,
} from '../dashboard/aggregate'

const DAY_MS = 24 * 60 * 60 * 1000
const MAX_PERIOD_DAYS = 90
const MAX_PAGE_SIZE = 200

export function invalidRequest(message: string) {
  return HttpResponse.json({ code: 'INVALID_REQUEST', message }, { status: 400 })
}

// 생략하면 to = 지금, from = to - 7일. 잘못된 값이면 400 응답을 돌려준다.
export function parsePeriod(params: URLSearchParams): { from: Date; to: Date } | { error: Response } {
  const toParam = params.get('to')
  const fromParam = params.get('from')
  const to = toParam ? new Date(toParam) : new Date()
  const from = fromParam ? new Date(fromParam) : new Date(to.getTime() - 7 * DAY_MS)
  if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) {
    return { error: invalidRequest('from/to는 ISO-8601 시각이어야 합니다.') }
  }
  if (from.getTime() >= to.getTime()) {
    return { error: invalidRequest('from은 to보다 이전이어야 합니다.') }
  }
  if (to.getTime() - from.getTime() > MAX_PERIOD_DAYS * DAY_MS) {
    return { error: invalidRequest(`조회 기간은 최대 ${MAX_PERIOD_DAYS}일입니다.`) }
  }
  return { from, to }
}

export function intParam(params: URLSearchParams, name: string, fallback: number): number | null {
  const raw = params.get(name)
  if (raw === null || raw === '') return fallback
  const value = Number(raw)
  return Number.isInteger(value) && value >= 0 ? value : null
}

export function parsePaging(params: URLSearchParams): { page: number; size: number } | { error: Response } {
  const page = intParam(params, 'page', 0)
  const size = intParam(params, 'size', 20)
  if (page === null || size === null || size < 1) return { error: invalidRequest('page/size가 올바르지 않습니다.') }
  return { page, size: Math.min(size, MAX_PAGE_SIZE) }
}

export const dashboardHandlers = [
  http.get(API_BASE_URL + '/api/admin/dashboard/summary', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error
    const period = parsePeriod(new URL(request.url).searchParams)
    if ('error' in period) return period.error
    return HttpResponse.json(computeSummary(getDashboardDataset(), period.from, period.to))
  }),

  http.get(API_BASE_URL + '/api/admin/dashboard/unacknowledged-blocks', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error
    const paging = parsePaging(new URL(request.url).searchParams)
    if ('error' in paging) return paging.error
    const { page, size } = paging
    const all = listUnacknowledged(getDashboardDataset())
    return HttpResponse.json({
      content: all.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: all.length,
      totalPages: Math.ceil(all.length / size),
    })
  }),

  http.get(API_BASE_URL + '/api/admin/dashboard/threats', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error
    const params = new URL(request.url).searchParams
    const period = parsePeriod(params)
    if ('error' in period) return period.error
    const size = intParam(params, 'size', 10)
    if (size === null || size < 1) return invalidRequest('size가 올바르지 않습니다.')
    return HttpResponse.json({
      content: computeThreats(getDashboardDataset(), period.from, period.to, Math.min(size, MAX_PAGE_SIZE)),
    })
  }),

  http.get(API_BASE_URL + '/api/admin/dashboard/engine-status', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error
    return HttpResponse.json(computeEngineStatus(getDashboardDataset(), new Date()))
  }),

  http.get(API_BASE_URL + '/api/admin/dashboard/daily-blocks', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error
    const params = new URL(request.url).searchParams
    const period = parsePeriod(params)
    if ('error' in period) return period.error
    const tz = params.get('tz') || 'Asia/Seoul'
    try {
      new Intl.DateTimeFormat('en-CA', { timeZone: tz })
    } catch {
      return invalidRequest('tz가 올바른 시간대가 아닙니다.')
    }
    const stackBy: StackBy = params.get('stackBy') === 'RULE' ? 'RULE' : 'SOURCE'
    return HttpResponse.json(computeDailyBlocks(getDashboardDataset(), period.from, period.to, tz, stackBy))
  }),

  http.get(API_BASE_URL + '/api/admin/dashboard/top-sources', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error
    const params = new URL(request.url).searchParams
    const period = parsePeriod(params)
    if ('error' in period) return period.error
    const by = params.get('by') === 'HOST' ? 'HOST' : 'AGENT'
    const size = intParam(params, 'size', 5)
    if (size === null || size < 1) return invalidRequest('size가 올바르지 않습니다.')
    return HttpResponse.json({
      by,
      content: computeTopSources(getDashboardDataset(), period.from, period.to, by, Math.min(size, MAX_PAGE_SIZE)),
    })
  }),
]
