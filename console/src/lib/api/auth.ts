import { apiRequest } from './client'

export type AdminRole = 'VIEWER' | 'ANALYST' | 'ADMIN'

export type LoginRequest = {
  username: string
  password: string
}

export type LoginResponse = {
  accessToken: string
  refreshToken?: string
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
  })
}

export function getMe() {
  return apiRequest<CurrentAdmin>('/api/admin/auth/me')
}
