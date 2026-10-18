import { notifyUnauthorized } from '../auth/events'

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

// 로그인은 서버 세션 쿠키(HttpOnly)라 콘솔이 토큰을 들고 있지 않는다.
// 콘솔과 API가 다른 사이트이므로 credentials: 'include'여야 쿠키가 실린다.
export async function apiRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const { headers, ...requestInit } = options
  const response = await fetch(API_BASE_URL + path, {
    ...requestInit,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(requestInit.body ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
  })

  const contentType = response.headers.get('content-type') ?? ''
  const body = contentType.includes('application/json')
    ? await response.json()
    : await response.text()

  if (!response.ok) {
    if (response.status === 401 && path !== '/api/admin/auth/login') {
      notifyUnauthorized()
    }

    throw new ApiError(response.status, body)
  }

  return body as T
}
