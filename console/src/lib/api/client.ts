import { notifyUnauthorized } from '../auth/events'
import { clearAccessToken, getAccessToken, getRefreshToken, getSessionVersion, updateAuthTokens } from '../auth/tokenStore'
import type { AuthTokens } from '../auth/tokenStore'

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') ?? 'http://localhost:8080'

export class ApiError extends Error {
  status: number
  body: unknown

  constructor(status: number, body: unknown) {
    super('API request failed with status ' + status)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

type ApiRequestOptions = RequestInit & {
  token?: string
  auth?: boolean
}

// refresh 토큰은 일회용이므로 동시에 실패한 요청들이 하나의 갱신 Promise를 공유한다.
let refreshFlight: { version: number; promise: Promise<void>; revocation: Promise<boolean> } | null = null

// 로그아웃은 토큰을 지우기 전에 해당 세대의 정리 결과를 잡아 둔다.
// 세션 계층을 역으로 import하지 않아 의존성은 session → client 방향을 유지한다.
export function pendingRefreshRevocation(version: number) {
  return refreshFlight?.version === version ? refreshFlight.revocation : null
}

function sessionChanged() {
  return new DOMException('인증 상태가 변경되어 요청 결과를 사용하지 않습니다.', 'AbortError')
}

function invalidateSession(version: number) {
  if (getSessionVersion() !== version) return
  clearAccessToken()
  notifyUnauthorized()
}

async function readBody(response: Response) {
  if (response.status === 204) return undefined
  return response.headers.get('content-type')?.includes('application/json')
    ? response.json() : response.text()
}

async function renewSession(version: number) {
  const refreshToken = getRefreshToken()
  if (!refreshToken) throw new ApiError(401, null)
  const response = await fetch(API_BASE_URL + '/api/admin/auth/refresh', {
    method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }), signal: AbortSignal.timeout(10_000),
  })
  const body = await readBody(response)
  if (!response.ok) throw new ApiError(response.status, body)
  if (!body || typeof body.accessToken !== 'string' || !body.accessToken || typeof body.refreshToken !== 'string' || !body.refreshToken) {
    throw new Error('토큰 갱신 응답 형식이 올바르지 않습니다.')
  }
  const tokens: AuthTokens = body
  if (!updateAuthTokens(tokens, version)) {
    // 로그아웃 중 이미 회전된 새 refresh 토큰도 가능한 한 폐기해 서버에 남기지 않는다.
    // HTTP 실패도 네트워크 실패도 폐기 확인이 아니다. 요청의 AbortError와 별도로 결과를 전달한다.
    try {
      const cleanup = await fetch(API_BASE_URL + '/api/admin/auth/logout', {
        method: 'POST', headers: { Authorization: 'Bearer ' + tokens.accessToken, 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: tokens.refreshToken }), signal: AbortSignal.timeout(10_000),
      })
      return cleanup.ok
    } catch {
      return false
    }
  }
  return true
}

function refreshOnce(version: number) {
  if (refreshFlight?.version === version) return refreshFlight.promise
  const renewal = renewSession(version)
  // 갱신이 401이면 새 토큰이 없지만, 통신/응답 오류라면 서버 발급 여부를 단정할 수 없다.
  const revocation = renewal.catch((error: unknown) => error instanceof ApiError && error.status === 401)
  const promise = renewal.then(() => {
    if (getSessionVersion() !== version) throw sessionChanged()
  }).catch((error: unknown) => {
    // 장애와 자격 거절을 구분한다. 이전 세대의 실패도 새 로그인에는 영향을 주지 않는다.
    if (getSessionVersion() !== version) throw sessionChanged()
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) invalidateSession(version)
    throw error
  }).finally(() => {
    if (refreshFlight?.promise === promise) refreshFlight = null
  })
  refreshFlight = { version, promise, revocation }
  return promise
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { token, auth = true, headers, ...requestInit } = options
  const version = getSessionVersion()
  // 명시적 token은 로그아웃처럼 '이전 세션'을 대상으로 하는 요청이다. 자동 갱신하지 않는다.
  const sessionRequest = auth && token === undefined
  const accessToken = auth ? token ?? getAccessToken() : null
  const send = (access: string | null) => fetch(API_BASE_URL + path, {
    ...requestInit,
    headers: {
      Accept: 'application/json',
      // FormData의 multipart 경계는 브라우저가 설정하며, 문자열 본문만 JSON으로 보낸다.
      ...(typeof requestInit.body === 'string' ? { 'Content-Type': 'application/json' } : {}),
      ...(access ? { Authorization: 'Bearer ' + access } : {}),
      ...headers,
    },
  })

  let response = await send(accessToken)
  if (sessionRequest && getSessionVersion() !== version) throw sessionChanged()
  if (sessionRequest && response.status === 401) {
    // 늦게 도착한 401이면 이미 갱신된 access를 재사용하고, 아니면 갱신을 한 번만 실행한다.
    if (!getAccessToken() || getAccessToken() === accessToken) await refreshOnce(version)
    if (getSessionVersion() !== version) throw sessionChanged()
    requestInit.signal?.throwIfAborted()
    response = await send(getAccessToken())
    if (getSessionVersion() !== version) throw sessionChanged()
  }
  const body = await readBody(response)
  // 본문 수신/JSON 파싱 중에도 로그아웃할 수 있으므로 반환 직전에 한 번 더 확인한다.
  if (sessionRequest && getSessionVersion() !== version) throw sessionChanged()
  if (!response.ok) {
    if (sessionRequest && response.status === 401) invalidateSession(version)
    throw new ApiError(response.status, body)
  }

  return body as T
}
