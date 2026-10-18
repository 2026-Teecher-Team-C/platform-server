import { apiRequest } from './client'

export type AdminRole = 'VIEWER' | 'ANALYST' | 'ADMIN'

export type LoginRequest = {
  username: string
  password: string
}

export type CurrentAdmin = {
  adminId: string
  username: string
  role: AdminRole
  isActive: boolean
  lastLoginAt: string | null
}

// 성공하면 서버가 세션 쿠키를 내려주고 본문으로 현재 관리자를 준다.
export function login(body: LoginRequest) {
  return apiRequest<CurrentAdmin>('/api/admin/auth/login', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function logout() {
  return apiRequest<void>('/api/admin/auth/logout', { method: 'POST' })
}

export function getMe() {
  return apiRequest<CurrentAdmin>('/api/admin/auth/me')
}
