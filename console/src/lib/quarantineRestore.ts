import { ApiError } from './api/client'

export function quarantineRestoreErrorMessage(cause: unknown) {
  if (cause instanceof ApiError) {
    if (cause.status === 404) return '격리 파일을 찾을 수 없습니다. 상태를 새로고침하거나 목록을 확인해 주세요.'
    if (cause.status === 409) return '이미 복원됐거나 복원할 수 없는 상태입니다. 상태를 새로고침해 주세요.'
    if (cause.status === 400) return '격리 ID와 복원 사유의 입력값을 확인해 주세요.'
    if (cause.status === 401) return '로그인이 만료되었습니다. 다시 로그인해 주세요.'
    if (cause.status === 403) return '격리 파일을 복원할 권한이 없습니다.'
  }
  // 요청이 처리된 뒤 응답만 끊겼을 수 있다. 조회로 확인하기 전에는 같은 쓰기를 자동 재시도하지 않는다.
  return '복원 결과를 확인하지 못했습니다. 서버에서 처리됐을 수 있으니 상태를 새로고침해 주세요.'
}
