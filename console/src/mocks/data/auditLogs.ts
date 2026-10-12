import type { AuditLogItem } from '../../lib/api/auditLogs'

export const mockAuditLogs: AuditLogItem[] = [
  {
    logId: 10482,
    action: 'ADD_WHITELIST',
    dangerous: true,
    actor: {
      adminId: '00000000-0000-4000-8000-000000000001',
      username: 'admin',
      role: 'ADMIN',
    },
    targetType: 'WHITELIST',
    targetId: 'SHA256:' + 'c8'.repeat(32),
    detail: { after: { reason: '사내 배포용 파일 사전 등록' } },
    ipAddress: '10.0.3.17',
    createdAt: '2026-10-11T08:12:55Z',
  },
  {
    logId: 10481,
    action: 'LOGIN_FAILED',
    dangerous: false,
    actor: null,
    targetType: 'ADMIN_USER',
    targetId: 'admin',
    detail: {
      attempted_username: 'admin',
      reason: 'BAD_PASSWORD',
      attempt_count: 3,
    },
    ipAddress: '203.0.113.9',
    createdAt: '2026-10-11T07:58:02Z',
  },
]

export function appendMockAuditLog(item: Omit<AuditLogItem, 'logId' | 'createdAt'>) {
  const nextId = Math.max(...mockAuditLogs.map((log) => log.logId), 0) + 1
  mockAuditLogs.unshift({
    ...item,
    logId: nextId,
    createdAt: new Date().toISOString(),
  })
}
