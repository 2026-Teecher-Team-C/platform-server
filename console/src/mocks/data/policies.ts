import type {
  BypassDomainItem,
  FileTypePolicyItem,
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
