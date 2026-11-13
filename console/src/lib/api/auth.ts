import { apiRequest } from './client'

export type AdminRole = 'USER' | 'ANALYST' | 'ADMIN'

export type LoginRequest = {
  username: string
  password: string
}

export type LoginResponse = {
  accessToken: string
  refreshToken: string
  tokenType: string
}

export type CurrentAdmin = {
  adminId: string
  username: string
  role: AdminRole
  isActive: boolean
  lastLoginAt: string | null
}

export function login(body: LoginRequest) {
  return apiRequest<LoginResponse>('/api/admin/auth/login', {
    method: 'POST',
    body: JSON.stringify(body),
    auth: false,
  })
}

export function getMe() {
  return apiRequest<CurrentAdmin>('/api/admin/auth/me')
}

// 로그아웃을 시작할 때의 토큰만 폐기해 이후의 다른 로그인에 영향을 주지 않는다.
export function logout(tokens: { accessToken: string; refreshToken: string | null }) {
  return apiRequest<void>('/api/admin/auth/logout', {
    method: 'POST', token: tokens.accessToken,
    body: JSON.stringify({ refreshToken: tokens.refreshToken }),
    signal: AbortSignal.timeout(10_000),
  })
}
