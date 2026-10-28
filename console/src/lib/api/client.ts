import { notifyUnauthorized } from '../auth/events'
import { getAccessToken } from '../auth/tokenStore'

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
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { token, headers, ...requestInit } = options
  const accessToken = token ?? getAccessToken()
  const response = await fetch(API_BASE_URL + path, {
    ...requestInit,
    headers: {
      Accept: 'application/json',
      // FormData는 브라우저가 multipart 경계를 붙여 보내야 하므로 문자열 본문일 때만 JSON으로 지정한다.
      ...(typeof requestInit.body === 'string' ? { 'Content-Type': 'application/json' } : {}),
      ...(accessToken ? { Authorization: 'Bearer ' + accessToken } : {}),
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
