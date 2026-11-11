import { http, HttpResponse } from 'msw'
import type { CreateEnrollmentTokenRequest, CreateEnrollmentTokenResponse } from '../../lib/api/agents'
import { API_BASE_URL } from '../../lib/api/client'
import { authorizeMockRequest } from '../http'
import { mockAgents, toMockAgentDetail } from '../data/agents'

const MOCK_DEFAULT_EXPIRY_MINUTES = 60

export const agentHandlers = [
  http.post(
    API_BASE_URL + '/api/admin/agents/enrollment-tokens',
    async ({ request }) => {
      // 서버는 역할을 세분화하지 않으므로 로그인 여부만 확인한다.
      const auth = authorizeMockRequest(request)
      if (auth.error) return auth.error

      const body = (await request.json().catch(() => ({}))) as
        | CreateEnrollmentTokenRequest
        | null
      const expiresInMinutes =
        body?.expiresInMinutes ?? MOCK_DEFAULT_EXPIRY_MINUTES

      if (!Number.isInteger(expiresInMinutes) || expiresInMinutes < 1 || expiresInMinutes > 1440) {
        return HttpResponse.json(
          {
            code: 'INVALID_INPUT',
            message: 'expiresInMinutes는 1 이상 1440 이하의 정수여야 합니다.',
          },
          { status: 400 },
        )
      }

      return HttpResponse.json(
        {
          // randomUUID 없이도 사설 HTTP 개발 주소에서 보안 난수를 사용할 수 있다.
          token: 'mock-enrollment-' + Array.from(crypto.getRandomValues(new Uint8Array(32)), (value) => value.toString(16).padStart(2, '0')).join(''),
          expiresAt: new Date(
            Date.now() + expiresInMinutes * 60 * 1000,
          ).toISOString(),
        } satisfies CreateEnrollmentTokenResponse,
        { status: 201 },
      )
    },
  ),

  http.get(API_BASE_URL + '/api/admin/agents', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const url = new URL(request.url)
    const status = url.searchParams.get('status')?.trim()
    const hostname = url.searchParams.get('hostname')?.trim().toLowerCase()
    const osPlatform = url.searchParams.get('osPlatform')?.trim()
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    // 실제 컨트롤러와 같은 허용값, 범위를 검사해 mock에서만 성공하는 요청을 막는다.
    if ((status && !['ACTIVE', 'INACTIVE', 'REVOKED'].includes(status)) ||
        (osPlatform && !['WINDOWS', 'MACOS', 'LINUX'].includes(osPlatform)) ||
        !Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 200) {
      return HttpResponse.json({ code: 'INVALID_INPUT', message: '조회 조건을 확인해 주세요.' }, { status: 400 })
    }

    const items = mockAgents.filter((agent) => {
      if (status && agent.status !== status) return false
      if (hostname && !agent.hostname.toLowerCase().includes(hostname)) return false
      if (osPlatform && agent.osPlatform !== osPlatform) return false
      return true
    }).sort((a, b) => {
      // 서버의 DESC NULLS LAST, agent_id 순서를 재현한다. 원본 배열은 바꾸지 않는다.
      if (a.lastHeartbeatAt === null && b.lastHeartbeatAt !== null) return 1
      if (a.lastHeartbeatAt !== null && b.lastHeartbeatAt === null) return -1
      const time = a.lastHeartbeatAt && b.lastHeartbeatAt
        ? Date.parse(b.lastHeartbeatAt) - Date.parse(a.lastHeartbeatAt) : 0
      return time || a.agentId.localeCompare(b.agentId)
    })
    // 서버와 같은 페이지 메타데이터로 실제 API 전환 시 응답 모양이 바뀌지 않게 한다.
    return HttpResponse.json({
      content: items.slice(page * size, (page + 1) * size),
      page, size, totalElements: items.length, totalPages: Math.ceil(items.length / size),
    })
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
