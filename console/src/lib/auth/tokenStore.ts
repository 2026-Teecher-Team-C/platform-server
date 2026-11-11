const ACCESS_TOKEN_KEY = 'teecher.console.accessToken'
const REFRESH_TOKEN_KEY = 'teecher.console.refreshToken'
let sessionVersion = 0

export type AuthTokens = { accessToken: string; refreshToken: string }

// 기존 access 저장 위치를 유지하고 refresh도 탭 단위 sessionStorage에 보관한다.
// 비밀번호는 보관하지 않는다. 두 토큰 모두 JS에서 읽을 수 있으므로 XSS 방어는 여전히 필요하다.
export function getSessionVersion() {
  return sessionVersion
}

export function getRefreshToken() {
  return storage()?.getItem(REFRESH_TOKEN_KEY) ?? null
}

export function setAuthTokens(tokens: AuthTokens) {
  sessionVersion += 1
  storage()?.setItem(ACCESS_TOKEN_KEY, tokens.accessToken)
  storage()?.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken)
}

// 갱신은 같은 로그인 안에서만 허용한다. 로그아웃 또는 다른 로그인 이후의 응답은 버린다.
export function updateAuthTokens(tokens: AuthTokens, expectedVersion: number) {
  if (sessionVersion !== expectedVersion) return false
  storage()?.setItem(ACCESS_TOKEN_KEY, tokens.accessToken)
  storage()?.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken)
  return true
}

// 브라우저 밖(Node 22 테스트 등)에는 sessionStorage가 없다. 그때는 토큰이 없는 것으로 본다.
function storage(): Storage | undefined {
  return typeof sessionStorage === 'undefined' ? undefined : sessionStorage
}

export function getAccessToken() {
  return storage()?.getItem(ACCESS_TOKEN_KEY) ?? null
}

export function setAccessToken(token: string) {
  sessionVersion += 1
  storage()?.setItem(ACCESS_TOKEN_KEY, token)
  storage()?.removeItem(REFRESH_TOKEN_KEY)
}

export function clearAccessToken() {
  sessionVersion += 1
  storage()?.removeItem(ACCESS_TOKEN_KEY)
  storage()?.removeItem(REFRESH_TOKEN_KEY)
}
