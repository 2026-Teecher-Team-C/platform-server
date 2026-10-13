import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { getMe, login as loginApi } from '../lib/api/auth'
import type { CurrentAdmin, LoginRequest } from '../lib/api/auth'
import {
  clearAccessToken,
  getAccessToken,
  setAccessToken,
} from '../lib/auth/tokenStore'
import { AUTH_UNAUTHORIZED_EVENT } from '../lib/auth/events'
import { AuthContext } from './auth-context'
import type { AuthContextValue } from './auth-context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentAdmin | null>(null)
  const [isInitializing, setIsInitializing] = useState(() => Boolean(getAccessToken()))

  useEffect(() => {
    function handleUnauthorized() {
      clearAccessToken()
      setUser(null)
      setIsInitializing(false)
    }

    window.addEventListener(AUTH_UNAUTHORIZED_EVENT, handleUnauthorized)

    return () => {
      window.removeEventListener(AUTH_UNAUTHORIZED_EVENT, handleUnauthorized)
    }
  }, [])

  useEffect(() => {
    const token = getAccessToken()

    if (!token) {
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
        clearAccessToken()
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
    const response = await loginApi(credentials)

    setAccessToken(response.accessToken)

    try {
      const currentUser = await getMe()
      setUser(currentUser)
      return currentUser
    } catch (error) {
      clearAccessToken()
      setUser(null)
      throw error
    }
  }

  function signOut() {
    clearAccessToken()
    setUser(null)
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
