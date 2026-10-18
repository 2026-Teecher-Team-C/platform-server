import { findMockAccountById, hasAtLeastRole } from './data/auth'
import type { AdminRole } from '../lib/api/auth'

// 실제 서버는 HttpOnly 세션 쿠키로 로그인 상태를 들고 있다. MSW는 브라우저 안에서 한 사람만 쓰므로
// 쿠키 대신 모듈 변수 하나를 세션으로 쓴다(새로고침하면 로그아웃된다).
let mockSessionAdminId: string | null = null

export function startMockSession(adminId: string) {
  mockSessionAdminId = adminId
}

export function endMockSession() {
  mockSessionAdminId = null
}

export function getAuthenticatedMockAccount() {
  if (!mockSessionAdminId) {
    return null
  }

  return findMockAccountById(mockSessionAdminId) ?? null
}

export function canAccessMockRole(required: AdminRole) {
  const account = getAuthenticatedMockAccount()

  return account ? hasAtLeastRole(account.role, required) : false
}
