import type { CurrentAdmin } from '../api/auth'

// 서버에 로그인 API가 생기기 전까지 VITE_AUTH_MODE=disabled로 로그인 없이 콘솔을 연다.
export function isAuthDisabled(mode: string | undefined) {
  return mode === 'disabled'
}

export const authDisabled = isAuthDisabled(import.meta.env.VITE_AUTH_MODE)

export const AUTH_DISABLED_ADMIN: CurrentAdmin = {
  adminId: 'auth-disabled',
  username: '인증 꺼짐',
  role: 'ADMIN',
  isActive: true,
  lastLoginAt: null,
}
