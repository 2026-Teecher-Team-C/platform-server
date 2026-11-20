import type {
  BypassDomainItem,
  FileTypePolicyItem,
  SimilarityPolicy,
} from '../../lib/api/policies'

export const mockBypassDomains: BypassDomainItem[] = [
  {
    bypassId: '50000000-0000-4000-8000-000000000001',
    host: 'updates.vendor.example',
    category: 'SECURITY_UPDATE',
    reason: '공급사 업데이트 인증서 피닝',
    isActive: true,
    createdAt: '2026-10-01T09:40:00+09:00',
  },
  {
    bypassId: '50000000-0000-4000-8000-000000000002',
    host: 'pinned.example.org',
    category: 'PINNED',
    reason: 'TLS 인증서 피닝으로 프록시 검사 불가',
    isActive: true,
    createdAt: '2026-10-02T11:20:00+09:00',
  },
]

export const mockFileTypePolicies: FileTypePolicyItem[] = [
  {
    fileTypePolicyId: '60000000-0000-4000-8000-000000000001',
    fileType: 'EXE',
    inspectionLevel: 'FULL',
    maxInspectSizeBytes: 104857600,
    oversizeAction: 'BLOCK',
    isActive: true,
    updatedBy: {
      adminId: '00000000-0000-4000-8000-000000000001',
      username: 'admin',
    },
    updatedAt: '2026-10-01T10:00:00+09:00',
    createdAt: '2026-09-01T09:00:00+09:00',
  },
  {
    fileTypePolicyId: '60000000-0000-4000-8000-000000000002',
    fileType: 'PDF',
    inspectionLevel: 'HASH_ONLY',
    maxInspectSizeBytes: null,
    oversizeAction: 'ALLOW',
    isActive: true,
    updatedBy: null,
    updatedAt: '2026-09-01T09:00:00+09:00',
    createdAt: '2026-09-01T09:00:00+09:00',
  },
]

// 서버 기본값(차단 30, 경고 70)과 허용 범위다. 허용 범위는 서버가 응답에 실어 주는 값이다.
export const defaultSimilarityPolicy: SimilarityPolicy = {
  blockDistance: 30,
  warnDistance: 70,
  limits: { blockMin: 0, blockMax: 50, warnMax: 70 },
}

// mock 핸들러가 PUT으로 바꾸는 현재 값이다. 위 목록들처럼 모듈 안의 상태를 직접 고친다.
export const mockSimilarityPolicy: SimilarityPolicy = {
  ...defaultSimilarityPolicy,
  limits: { ...defaultSimilarityPolicy.limits },
}

// 테스트가 값을 바꾸고 나면 기본값으로 되돌릴 때 쓴다. 서버 통합 테스트의 @AfterEach와 같은 이유다.
export function resetMockSimilarityPolicy() {
  mockSimilarityPolicy.blockDistance = defaultSimilarityPolicy.blockDistance
  mockSimilarityPolicy.warnDistance = defaultSimilarityPolicy.warnDistance
}
