import { apiRequest } from './client'
import type { PageResponse } from './types'
import { toQueryString } from './types'

// 실제 DB CHECK 제약(ck_quarantine_files_status)과 1:1.
export type QuarantineStatus = 'QUARANTINED' | 'RESTORED' | 'DELETED' | 'EXPIRED'

export type QuarantineListItem = {
  quarantineId: string
  eventId: string
  agentId: string
  hostname: string
  sha256: string
  // original_filename은 DB에서 nullable이다.
  originalFilename: string | null
  fileSize: number
  status: QuarantineStatus
  quarantinedAt: string
  restoredAt: string | null
}

export type QuarantineQuery = {
  status?: QuarantineStatus
  agentId?: string
  // page는 0부터. size 상한은 서버 기준 200.
  page?: number
  size?: number
}

export type RestoreQuarantineResponse = {
  quarantineId: string
  sha256: string
  status: 'RESTORED'
  whitelisted: true
  restoredAt: string
}

export function getQuarantineFiles(query: QuarantineQuery = {}) {
  return apiRequest<PageResponse<QuarantineListItem>>(
    '/api/admin/quarantine' + toQueryString(query),
  )
}

export function getQuarantineFile(quarantineId: string) {
  return apiRequest<QuarantineListItem & {
    downloadEvent?: {
      eventId: string
      requestHost: string
      url: string
    }
    agent?: {
      agentId: string
      hostname: string
    }
  }>('/api/admin/quarantine/' + encodeURIComponent(quarantineId))
}

export function restoreQuarantineFile(quarantineId: string, reason?: string) {
  // 선택 사유가 없으면 본문을 생략해 서버 기본값인 '오탐 복원'을 사용한다. 파일 바이트를 내려받는 요청은 아니다.
  const trimmed = reason?.trim()
  return apiRequest<RestoreQuarantineResponse>(
    '/api/admin/quarantine/' + encodeURIComponent(quarantineId) + '/restore',
    { method: 'POST', ...(trimmed ? { body: JSON.stringify({ reason: trimmed }) } : {}) },
  )
}
