import { HttpResponse } from 'msw'
import type { AdminRole } from '../lib/api/auth'
import { getAuthenticatedMockAccount } from './auth'
import { hasAtLeastRole } from './data/auth'

// 실제 서버는 아직 역할로 막지 않는다(로그인 여부만 본다). mock은 역할 화면을 미리 보려고 남겨 둔다.
export function authorizeMockRequest(_request: Request, required: AdminRole = 'VIEWER') {
  const account = getAuthenticatedMockAccount()

  if (!account) {
    return {
      account: null,
      error: HttpResponse.json(
        { status: 401, code: 'UNAUTHORIZED', message: '로그인이 필요합니다.', fieldErrors: [] },
        { status: 401 },
      ),
    }
  }

  if (!hasAtLeastRole(account.role, required)) {
    return {
      account: null,
      error: HttpResponse.json(
        { status: 403, code: 'FORBIDDEN', message: '접근 권한이 없습니다.', fieldErrors: [] },
        { status: 403 },
      ),
    }
  }

  return { account, error: null }
}

export function isSha256(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-fA-F]{64}$/.test(value)
}
