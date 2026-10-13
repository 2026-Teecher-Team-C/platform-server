import type { BlacklistItem, WhitelistItem } from '../../lib/api/hashlists'

export const mockWhitelist: WhitelistItem[] = [
  {
    hashType: 'SHA256',
    hashValue: '7a'.repeat(32),
    reason: '오탐 확인 - 정상 설치 프로그램',
    origin: 'RESTORED',
    originQuarantineId: '40000000-0000-4000-8000-000000000002',
    addedBy: {
      adminId: '00000000-0000-4000-8000-000000000001',
      username: 'admin',
    },
    isActive: true,
    expiresAt: null,
    createdAt: '2026-10-10T11:00:00+09:00',
  },
  {
    hashType: 'SHA256',
    hashValue: 'c8'.repeat(32),
    reason: '사내 배포용 파일 사전 등록',
    origin: 'PREREGISTERED',
    originQuarantineId: null,
    addedBy: {
      adminId: '00000000-0000-4000-8000-000000000001',
      username: 'admin',
    },
    isActive: true,
    expiresAt: null,
    createdAt: '2026-10-09T09:30:00+09:00',
  },
]

export const mockBlacklist: BlacklistItem[] = [
  {
    hashType: 'SHA256',
    hashValue: '55'.repeat(32),
    reason: 'YARA 룰 매치 - 테스트 악성 샘플',
    severity: 'HIGH',
    source: 'MANUAL',
    addedBy: {
      adminId: '00000000-0000-4000-8000-000000000001',
      username: 'admin',
    },
    isActive: true,
    createdAt: '2026-10-11T10:12:00+09:00',
    deactivatedAt: null,
  },
  {
    hashType: 'SHA256',
    hashValue: '9b'.repeat(32),
    reason: '외부 피드에서 가져온 테스트 항목',
    severity: 'CRITICAL',
    source: 'IMPORT',
    addedBy: null,
    isActive: true,
    createdAt: '2026-10-08T02:00:00+09:00',
    deactivatedAt: null,
  },
]
