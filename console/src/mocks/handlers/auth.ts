import { http, HttpResponse } from 'msw'
import type { LoginRequest } from '../../lib/api/auth'
import { API_BASE_URL } from '../../lib/api/client'
import { getAuthenticatedMockAccount } from '../auth'
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

    return HttpResponse.json({
      accessToken: account.accessToken,
      refreshToken: account.refreshToken,
      tokenType: 'Bearer',
    })
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
