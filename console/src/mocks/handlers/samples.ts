import { http, HttpResponse } from 'msw'
import { API_BASE_URL } from '../../lib/api/client'
import type { SampleDetail } from '../../lib/api/samples'
import { authorizeMockRequest } from '../http'
import { mockDownloadEvents } from '../data/events'

// 모의 화면 전용 현재 행. 이벤트 이력에서 실제 출처를 추정했다는 의미가 아니다.
const samples = new Map<string, SampleDetail>(mockDownloadEvents.flatMap(event => {
  const sha256 = event.similarity?.matchedSampleSha256
  return sha256 ? [[sha256, { sha256,
    blacklist: { reason: 'Synthetic console review sample', source: 'MANUAL', severity: 'HIGH', isActive: true, registeredAt: '2026-10-20T00:00:00Z' },
    fingerprint: { tlsh: 'T1' + 'A'.repeat(70), calculationStatus: 'CALCULATED', algorithmVersion: 'synthetic-display-fixture', provenance: 'ADMIN_IMPORT', provenanceDetail: 'Synthetic console fixture, not an external feed', calculatedAt: '2026-10-20T00:00:00Z' },
  }]] : []
}))
export const sampleHandlers = [http.get(API_BASE_URL + '/api/admin/samples/:sha256', ({ request, params }) => {
  const auth = authorizeMockRequest(request)
  if (auth.error) return auth.error
  const sha = String(params.sha256)
  if (!/^[0-9a-fA-F]{64}$/.test(sha)) return HttpResponse.json({ code: 'INVALID_INPUT' }, { status: 400 })
  const sample = samples.get(sha.toLowerCase())
  return sample ? HttpResponse.json(sample) : HttpResponse.json({ code: 'NOT_FOUND' }, { status: 404 })
})]
