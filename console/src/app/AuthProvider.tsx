import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { getMe } from '../lib/api/auth'
import type { CurrentAdmin, LoginRequest } from '../lib/api/auth'
import {
  getAccessToken,
  getRefreshToken,
  getSessionVersion,
} from '../lib/auth/tokenStore'
import { endSession, establishSession } from '../lib/auth/session'
import { AUTH_DISABLED_ADMIN, authDisabled } from '../lib/auth/authMode'
import { AUTH_UNAUTHORIZED_EVENT } from '../lib/auth/events'
import { AuthContext } from './auth-context'
import type { AuthContextValue } from './auth-context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentAdmin | null>(() =>
    authDisabled ? AUTH_DISABLED_ADMIN : null,
  )
  const [isInitializing, setIsInitializing] = useState(
    () => !authDisabled && Boolean(getAccessToken() || getRefreshToken()),
  )
  const [authNotice, setAuthNotice] = useState('')

  useEffect(() => {
    if (authDisabled) {
      return
    }

    function handleUnauthorized() {
      setUser(null)
      setIsInitializing(false)
      setAuthNotice('로그인 세션이 만료되었습니다. 다시 로그인해 주세요.')
    }

    window.addEventListener(AUTH_UNAUTHORIZED_EVENT, handleUnauthorized)

    return () => {
      window.removeEventListener(AUTH_UNAUTHORIZED_EVENT, handleUnauthorized)
    }
  }, [])

  useEffect(() => {
    const token = getAccessToken()

    if (authDisabled || (!token && !getRefreshToken())) {
      return
    }

    let cancelled = false
    const version = getSessionVersion()

    getMe()
      .then((currentUser) => {
        if (!cancelled && getSessionVersion() === version) {
          setUser(currentUser)
        }
      })
      .catch(() => {
        // 정리된 effect 또는 이전 로그인 요청이 새 인증 결과를 지우지 않도록 확인한다.
        if (!cancelled && getSessionVersion() === version) {
          setUser(null)
          setAuthNotice('인증 상태를 확인하지 못했습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.')
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
    setAuthNotice('')
    const result = await establishSession(credentials)
    if (getSessionVersion() !== result.version) throw new DOMException('인증 상태가 변경되었습니다.', 'AbortError')
    setUser(result.user)
    return result.user
  }

  async function signOut() {
    if (authDisabled) {
      return
    }

    const pending = endSession()
    const version = getSessionVersion()
    setUser(null)
    setAuthNotice('')
    const serverRevoked = await pending
    // 그사이 새로 로그인했다면 이전 로그아웃의 안내를 보여주지 않는다.
    if (!serverRevoked && getSessionVersion() === version) {
      setAuthNotice('이 기기에서는 로그아웃됐지만 서버의 토큰 폐기를 확인하지 못했습니다. 공용 기기라면 모든 콘솔 탭을 닫아 주세요.')
    }
  }

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isInitializing,
      authNotice,
      isAuthenticated: user !== null,
      signIn,
      signOut,
    }),
    [user, isInitializing, authNotice],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
