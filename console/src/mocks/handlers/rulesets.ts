import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import { authorizeMockRequest } from '../http'
import { mockRulesets } from '../data/rulesets'

export const rulesetHandlers = [
  http.get(API_BASE_URL + '/api/admin/rulesets', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    return HttpResponse.json({
      items: mockRulesets,
      nextCursor: null,
    })
  }),
]
