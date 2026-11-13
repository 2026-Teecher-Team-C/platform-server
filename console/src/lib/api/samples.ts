import { apiRequest } from './client'

// 현재 DB 조회 계약이며 이벤트의 과거 평가 스냅샷과 분리한다.
export type SampleDetail = {
  sha256: string
  blacklist: { reason: string | null; source: string; severity: string; isActive: boolean; registeredAt: string } | null
  fingerprint: { tlsh: string | null; calculationStatus: string; algorithmVersion: string; provenance: string; provenanceDetail: string | null; calculatedAt: string } | null
}

export function getSample(sha256: string) {
  return apiRequest<SampleDetail>('/api/admin/samples/' + encodeURIComponent(sha256))
}
