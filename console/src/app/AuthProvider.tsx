import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { getMe, login as loginApi, logout as logoutApi } from '../lib/api/auth'
import type { CurrentAdmin, LoginRequest } from '../lib/api/auth'
import { AUTH_DISABLED_ADMIN, authDisabled } from '../lib/auth/authMode'
import { AUTH_UNAUTHORIZED_EVENT } from '../lib/auth/events'
import { AuthContext } from './auth-context'
import type { AuthContextValue } from './auth-context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentAdmin | null>(() =>
    authDisabled ? AUTH_DISABLED_ADMIN : null,
  )
  // 세션 쿠키는 HttpOnly라 읽을 수 없으므로 로그인 여부는 항상 /me로 확인한다.
  const [isInitializing, setIsInitializing] = useState(() => !authDisabled)

  useEffect(() => {
    if (authDisabled) {
      return
    }

    function handleUnauthorized() {
      setUser(null)
      setIsInitializing(false)
    }

    window.addEventListener(AUTH_UNAUTHORIZED_EVENT, handleUnauthorized)

    return () => {
      window.removeEventListener(AUTH_UNAUTHORIZED_EVENT, handleUnauthorized)
    }
  }, [])

  useEffect(() => {
    if (authDisabled) {
      return
    }

    let cancelled = false

    getMe()
      .then((currentUser) => {
        if (!cancelled) {
          setUser(currentUser)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setUser(null)
        }
      })
      .finally(() => {
        if (!cancelled) {
          setIsInitializing(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [])

  async function signIn(credentials: LoginRequest) {
    const currentUser = await loginApi(credentials)
    setUser(currentUser)
    return currentUser
  }

  async function signOut() {
    if (authDisabled) {
      return
    }

    try {
      await logoutApi()
    } catch {
      // 세션이 이미 끊겼거나 서버에 닿지 않아도 화면은 로그아웃 상태로 돌린다.
    } finally {
      setUser(null)
    }
  }

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isInitializing,
      isAuthenticated: user !== null,
      signIn,
      signOut,
    }),
    [user, isInitializing],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
