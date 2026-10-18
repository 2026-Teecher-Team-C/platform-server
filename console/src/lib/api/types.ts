// 서버 목록 응답(api-server common/PageResponse)과 1:1. page는 0부터 시작한다.
export type PageResponse<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// 폐기 예정(2026-10-18 팀 결정: 목록은 PageResponse). 아직 서버가 없는 API의 목 응답만 이 모양을 쓴다.
export type CursorPage<T> = {
  items: T[]
  nextCursor: string | null
}

export function toQueryString(params: Record<string, string | number | boolean | null | undefined>) {
  const search = new URLSearchParams()

  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value))
    }
  }

  const query = search.toString()
  return query ? '?' + query : ''
}
