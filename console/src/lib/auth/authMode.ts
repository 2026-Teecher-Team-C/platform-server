import type { CurrentAdmin } from '../api/auth'

// VITE_AUTH_MODE=disabled면 로그인 없이 콘솔을 연다. 서버 로그인(세션 쿠키)을 쓰려면 enabled로 둔다.
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
