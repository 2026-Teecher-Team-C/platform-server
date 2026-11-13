import { ApiError, apiRequest, pendingRefreshRevocation } from '../api/client'
import { getMe, login, logout } from '../api/auth'
import type { LoginRequest, LoginResponse } from '../api/auth'
import { clearAccessToken, getAccessToken, getRefreshToken, getSessionVersion, setAuthTokens } from './tokenStore'

export async function establishSession(credentials: LoginRequest) {
  const beforeLogin = getSessionVersion()
  const tokens = await login(credentials)
  // 로그인 요청 중 로그아웃하거나 다른 세션이 시작되었다면 늦은 응답은 폐기한다.
  if (getSessionVersion() !== beforeLogin) {
    await logout(tokens).catch(() => {})
    throw new DOMException('취소된 로그인입니다.', 'AbortError')
  }
  setAuthTokens(tokens)
  const version = getSessionVersion()
  try {
    const user = await getMe()
    if (getSessionVersion() !== version) throw new DOMException('인증 상태가 변경되었습니다.', 'AbortError')
    return { user, version }
  } catch (error) {
    if (getSessionVersion() === version) clearAccessToken()
    throw error
  }
}

export async function endSession() {
  const accessToken = getAccessToken() ?? ''
  const refreshToken = getRefreshToken()
  const pendingRevocation = pendingRefreshRevocation(getSessionVersion())
  // 서버 응답이 느리거나 실패해도 현재 탭의 인증 정보와 요청 세대는 즉시 폐기한다.
  clearAccessToken()
  // 소비된 refresh의 401만으로 성공을 단정하지 않고, 늦게 발급된 토큰의 폐기도 확인한다.
  // 두 작업은 캡처한 이전 세션만 사용한다. 새 로그인 토큰/세대에는 손대지 않는다.
  const [revoked, refreshRevoked] = await Promise.all([
    revokeSessionTokens(accessToken, refreshToken), pendingRevocation ?? true,
  ])
  return revoked && refreshRevoked
}

async function revokeSessionTokens(accessToken: string, refreshToken: string | null) {
  if (!accessToken && !refreshToken) return true
  try {
    await logout({ accessToken, refreshToken })
    return true
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 401 && refreshToken)) return false
  }
  // access가 만료돼도 refresh는 유효할 수 있다. 저장하지 않고 교환 후 곧바로 폐기한다.
  let tokens: LoginResponse
  try {
    tokens = await apiRequest<LoginResponse>('/api/admin/auth/refresh', {
      method: 'POST', auth: false, body: JSON.stringify({ refreshToken }), signal: AbortSignal.timeout(10_000),
    })
  } catch (error) {
    // refresh 자체가 만료/폐기된 경우에는 추가로 폐기할 세션이 없다.
    return error instanceof ApiError && error.status === 401
  }
  try {
    await logout(tokens)
    return true
  } catch {
    // 새 토큰 발급 성공과 서버 폐기 성공은 다르다. 폐기 실패 안내를 숨기지 않는다.
    return false
  }
}
