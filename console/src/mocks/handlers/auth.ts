import { http, HttpResponse } from 'msw'
import type { LoginRequest } from '../../lib/api/auth'
import { API_BASE_URL } from '../../lib/api/client'
import { getAuthenticatedMockAccount } from '../auth'
import { issueMockSession, revokeMockSession, rotateMockSession } from '../sessions'
import {
  findMockAccountByCredentials,
  toCurrentAdmin,
} from '../data/auth'

const invalidCredentials = {
  code: 'INVALID_CREDENTIALS',
  message: '아이디 또는 비밀번호가 올바르지 않습니다.',
}

export const authHandlers = [
  http.post(API_BASE_URL + '/api/admin/auth/login', async ({ request }) => {
    const body = (await request.json().catch(() => null)) as
      | Partial<LoginRequest>
      | null

    if (
      !body ||
      typeof body.username !== 'string' ||
      body.username.trim() === '' ||
      typeof body.password !== 'string' ||
      body.password === ''
    ) {
      return HttpResponse.json(
        {
          code: 'VALIDATION_ERROR',
          message: 'username과 password는 필수입니다.',
        },
        { status: 400 },
      )
    }

    const account = findMockAccountByCredentials(
      body.username.trim(),
      body.password,
    )

    if (!account) {
      return HttpResponse.json(invalidCredentials, { status: 401 })
    }

    account.lastLoginAt = new Date().toISOString()

    return HttpResponse.json(issueMockSession(account))
  }),

  // 일회용 refresh를 교환해 실제 서버의 토큰 회전과 재사용 거부를 mock에서도 확인한다.
  http.post(API_BASE_URL + '/api/admin/auth/refresh', async ({ request }) => {
    const body = await request.json().catch(() => null) as { refreshToken?: unknown } | null
    const session = typeof body?.refreshToken === 'string' ? rotateMockSession(body.refreshToken) : null
    return session ? HttpResponse.json(session) : HttpResponse.json({ code: 'UNAUTHORIZED', message: '다시 로그인하세요.' }, { status: 401 })
  }),

  http.post(API_BASE_URL + '/api/admin/auth/logout', async ({ request }) => {
    const account = getAuthenticatedMockAccount(request)
    if (!account) return HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 })
    const body = await request.json().catch(() => null) as { refreshToken?: unknown } | null
    if (typeof body?.refreshToken === 'string') revokeMockSession(body.refreshToken, account.adminId)
    return new HttpResponse(null, { status: 204 })
  }),

  http.get(API_BASE_URL + '/api/admin/auth/me', ({ request }) => {
    const account = getAuthenticatedMockAccount(request)

    if (!account) {
      return HttpResponse.json(
        {
          code: 'UNAUTHORIZED',
          message: '인증 정보가 없거나 만료되었습니다.',
        },
        { status: 401 },
      )
    }

    return HttpResponse.json(toCurrentAdmin(account))
  }),
]
