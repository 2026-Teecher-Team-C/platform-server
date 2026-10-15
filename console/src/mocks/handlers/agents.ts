import { http, HttpResponse } from 'msw'
import type { CreateEnrollmentTokenRequest } from '../../lib/api/agents'
import { API_BASE_URL } from '../../lib/api/client'
import { authorizeMockRequest } from '../http'
import { mockAgents, toMockAgentDetail } from '../data/agents'

const MOCK_DEFAULT_EXPIRY_MINUTES = 30

export const agentHandlers = [
  http.post(
    API_BASE_URL + '/api/admin/agents/enrollment-tokens',
    async ({ request }) => {
      const auth = authorizeMockRequest(request, 'ADMIN')
      if (auth.error) return auth.error

      const body = (await request.json().catch(() => ({}))) as
        | CreateEnrollmentTokenRequest
        | null
      const expiresInMinutes =
        body?.expiresInMinutes ?? MOCK_DEFAULT_EXPIRY_MINUTES

      if (!Number.isInteger(expiresInMinutes) || expiresInMinutes <= 0) {
        return HttpResponse.json(
          {
            code: 'INVALID_EXPIRY',
            message: 'expiresInMinutes는 1 이상의 정수여야 합니다.',
          },
          { status: 400 },
        )
      }

      return HttpResponse.json(
        {
          token: 'mock-enrollment-' + crypto.randomUUID(),
          expiresAt: new Date(
            Date.now() + expiresInMinutes * 60 * 1000,
          ).toISOString(),
        },
        { status: 201 },
      )
    },
  ),

  http.get(API_BASE_URL + '/api/admin/agents', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    const hostname = url.searchParams.get('hostname')?.toLowerCase()
    const osPlatform = url.searchParams.get('osPlatform')

    const items = mockAgents.filter((agent) => {
      if (status && agent.status !== status) return false
      if (hostname && !agent.hostname.toLowerCase().includes(hostname)) return false
      if (osPlatform && agent.osPlatform !== osPlatform) return false
      return true
    })

    return HttpResponse.json({ items, nextCursor: null })
  }),

  http.get(API_BASE_URL + '/api/admin/agents/:agentId', ({ request, params }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const agent = mockAgents.find((item) => item.agentId === params.agentId)

    if (!agent) {
      return HttpResponse.json(
        { code: 'AGENT_NOT_FOUND', message: '해당 agentId를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }

    return HttpResponse.json(toMockAgentDetail(agent))
  }),
]
