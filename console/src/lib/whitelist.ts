import { ApiError } from './api/client'

const SHA256_PATTERN = /^[0-9a-fA-F]{64}$/

// 서버 검증(HashKey, @NotBlank)과 같은 조건을 먼저 확인해 불필요한 400 요청을 줄인다.
export function validateWhitelistForm(sha256: string, reason: string) {
  if (!SHA256_PATTERN.test(sha256.trim())) return 'SHA-256은 64자리 16진수여야 합니다.'
  if (!reason.trim()) return '등록 사유를 입력해 주세요.'
  return ''
}

// 서버 ErrorResponse의 code는 콘솔과 mock에서 이름이 달라 HTTP 상태로 구분한다.
export function whitelistErrorMessage(cause: unknown) {
  if (cause instanceof ApiError) {
    if (cause.status === 409) return '이미 화이트리스트에 있거나 블랙리스트에 등록된 해시입니다.'
    if (cause.status === 404) return '활성 화이트리스트 항목을 찾을 수 없습니다. 목록을 새로고침해 주세요.'
    if (cause.status === 400) {
      const message = (cause.body as { message?: unknown } | null)?.message
      return '입력값을 확인해 주세요.' + (typeof message === 'string' ? ' (' + message + ')' : '')
    }
  }
  return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 서버 목록의 includeExpired 기준과 같이 만료 시각이 지금 이전이거나 같으면 만료로 본다.
export function isWhitelistExpired(item: { expiresAt: string | null }, now: number) {
  return item.expiresAt !== null && new Date(item.expiresAt).getTime() <= now
}

// datetime-local 입력의 min 값. 브라우저 로컬 시각을 'YYYY-MM-DDTHH:mm'로 만든다.
export function toDateTimeLocal(date: Date) {
  const pad = (value: number) => String(value).padStart(2, '0')
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
    + 'T' + pad(date.getHours()) + ':' + pad(date.getMinutes())
}
