import type { QuarantineListItem } from '../../lib/api/quarantine'

export const mockQuarantine: QuarantineListItem[] = [
  {
    quarantineId: '40000000-0000-4000-8000-000000000001',
    eventId: '30000000-0000-4000-8000-000000000001',
    agentId: '10000000-0000-4000-8000-000000000001',
    hostname: 'DESKTOP-7F2K91',
    sha256: '44'.repeat(32),
    originalFilename: 'invoice_9821.exe',
    fileSize: 284672,
    status: 'QUARANTINED',
    quarantinedAt: '2026-10-11T17:41:31+09:00',
    restoredAt: null,
  },
  {
    quarantineId: '40000000-0000-4000-8000-000000000002',
    eventId: '30000000-0000-4000-8000-000000000002',
    agentId: '10000000-0000-4000-8000-000000000002',
    hostname: 'MACBOOK-DEV',
    sha256: '7a'.repeat(32),
    originalFilename: 'client.dmg',
    fileSize: 1456000,
    status: 'RESTORED',
    quarantinedAt: '2026-10-10T10:40:00+09:00',
    restoredAt: '2026-10-10T11:00:00+09:00',
  },
]
