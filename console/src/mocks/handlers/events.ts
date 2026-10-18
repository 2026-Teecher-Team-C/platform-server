import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import { authorizeMockRequest } from '../http'
import { mockDownloadEvents } from '../data/events'

export const eventHandlers = [
  http.get(API_BASE_URL + '/api/admin/download-events', ({ request }) => {
    const auth = authorizeMockRequest(request)
    if (auth.error) return auth.error

    const url = new URL(request.url)
    const agentId = url.searchParams.get('agentId')
    const decision = url.searchParams.get('decision')
    const from = url.searchParams.get('from')
    const to = url.searchParams.get('to')
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)

    const matched = mockDownloadEvents
      .filter((event) => {
        if (agentId && event.agentId !== agentId) return false
        if (decision && event.decision !== decision) return false
        if (from && new Date(event.createdAt) < new Date(from)) return false
        if (to && new Date(event.createdAt) >= new Date(to)) return false
        return true
      })
      .map(({ requestHost: _requestHost, url: _url, bytesUploaded: _bytesUploaded, heldAt: _heldAt, decidedAt: _decidedAt, ...event }) => event)

    // 서버 PageResponse와 같은 모양으로 한 페이지만 잘라 준다.
    return HttpResponse.json({
      content: matched.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: matched.length,
      totalPages: Math.ceil(matched.length / size),
    })
  }),

  http.get(
    API_BASE_URL + '/api/admin/download-events/:eventId',
    ({ request, params }) => {
      const auth = authorizeMockRequest(request)
      if (auth.error) return auth.error

      const event = mockDownloadEvents.find((item) => item.eventId === params.eventId)

      if (!event) {
        return HttpResponse.json(
          { code: 'DOWNLOAD_EVENT_NOT_FOUND', message: '해당 eventId를 찾을 수 없습니다.' },
          { status: 404 },
        )
      }

      return HttpResponse.json(event)
    },
  ),
]
