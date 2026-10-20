import { HttpResponse } from 'msw'
import type { AdminRole } from '../lib/api/auth'
import { getAuthenticatedMockAccount } from './auth'
import { hasAtLeastRole } from './data/auth'

export function authorizeMockRequest(request: Request, required: AdminRole = 'VIEWER') {
  const account = getAuthenticatedMockAccount(request)

  if (!account) {
    return {
      account: null,
      error: HttpResponse.json(
        { code: 'UNAUTHORIZED', message: '인증 정보가 없거나 만료되었습니다.' },
        { status: 401 },
      ),
    }
  }

  if (!hasAtLeastRole(account.role, required)) {
    return {
      account: null,
      error: HttpResponse.json(
        { code: 'FORBIDDEN', message: '이 리소스에 접근할 권한이 없습니다.' },
        { status: 403 },
      ),
    }
  }

  return { account, error: null }
}

export function isSha256(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-fA-F]{64}$/.test(value)
}
