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

type QueryValue = string | number | boolean | null | undefined

// 배열은 같은 키를 여러 번 붙인다(decision=A&decision=B). 빈 값(undefined/null/'')과 빈 배열은 버리고 false/0은 남긴다.
export function toQueryString(params: Record<string, QueryValue | QueryValue[]>) {
  const search = new URLSearchParams()

  for (const [key, value] of Object.entries(params)) {
    for (const item of Array.isArray(value) ? value : [value]) {
      if (item !== undefined && item !== null && item !== '') {
        search.append(key, String(item))
      }
    }
  }

  const query = search.toString()
  return query ? '?' + query : ''
}
