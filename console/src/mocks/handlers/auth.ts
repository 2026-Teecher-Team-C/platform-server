import { http, HttpResponse } from 'msw'
import type { LoginRequest } from '../../lib/api/auth'
import { API_BASE_URL } from '../../lib/api/client'
import {
  endMockSession,
  getAuthenticatedMockAccount,
  startMockSession,
} from '../auth'
import {
  findMockAccountByCredentials,
  toCurrentAdmin,
} from '../data/auth'

// 실제 서버의 ErrorResponse와 같은 모양으로 맞춘다(docs/api-convention.md).
function errorResponse(
  status: number,
  code: string,
  message: string,
  path: string,
  fieldErrors: { field: string; reason: string }[] = [],
) {
  return HttpResponse.json(
    { timestamp: new Date().toISOString(), status, code, message, path, fieldErrors },
    { status },
  )
}

export const authHandlers = [
  http.post(API_BASE_URL + '/api/admin/auth/login', async ({ request }) => {
    const path = '/api/admin/auth/login'
    const body = (await request.json().catch(() => null)) as
      | Partial<LoginRequest>
      | null

    const fieldErrors: { field: string; reason: string }[] = []
    if (typeof body?.username !== 'string' || body.username.trim() === '') {
      fieldErrors.push({ field: 'username', reason: '공백일 수 없습니다' })
    }
    if (typeof body?.password !== 'string' || body.password.trim() === '') {
      fieldErrors.push({ field: 'password', reason: '공백일 수 없습니다' })
    }
    if (!body || fieldErrors.length > 0) {
      return errorResponse(400, 'INVALID_INPUT', '입력값이 올바르지 않습니다.', path, fieldErrors)
    }

    // 실제 서버처럼 없는 계정·틀린 비밀번호·비활성 계정을 구분하지 않는다.
    const account = findMockAccountByCredentials(
      body.username as string,
      body.password as string,
    )

    if (!account) {
      return errorResponse(401, 'UNAUTHORIZED', '아이디 또는 비밀번호가 올바르지 않습니다.', path)
    }

    account.lastLoginAt = new Date().toISOString()
    startMockSession(account.adminId)

    return HttpResponse.json(toCurrentAdmin(account))
  }),

  http.post(API_BASE_URL + '/api/admin/auth/logout', () => {
    if (!getAuthenticatedMockAccount()) {
      return errorResponse(401, 'UNAUTHORIZED', '로그인이 필요합니다.', '/api/admin/auth/logout')
    }

    endMockSession()

    return new HttpResponse(null, { status: 204 })
  }),

  http.get(API_BASE_URL + '/api/admin/auth/me', () => {
    const account = getAuthenticatedMockAccount()

    if (!account) {
      return errorResponse(401, 'UNAUTHORIZED', '로그인이 필요합니다.', '/api/admin/auth/me')
    }

    return HttpResponse.json(toCurrentAdmin(account))
  }),
]
