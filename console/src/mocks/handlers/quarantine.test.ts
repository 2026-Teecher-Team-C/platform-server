import { afterAll, afterEach, beforeAll, expect, it, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { quarantineHandlers } from './quarantine'
import { restoreQuarantineFile } from '../../lib/api/quarantine'
import { mockQuarantine } from '../data/quarantine'
import { mockWhitelist, mockBlacklist } from '../data/hashlists'
import { mockAuditLogs } from '../data/auditLogs'

// 복원 업무 계약을 검사한다. 실제 로그인/역할 검증을 대신하는 테스트는 아니다.
vi.mock('../http', async (original) => ({ ...await original<typeof import('../http')>(), authorizeMockRequest: () => ({ error: null, account: { adminId: 'qa-admin', username: 'qa', role: 'ADMIN' } }) }))
const originalQuarantine = mockQuarantine.map((row) => ({ ...row }))
const originalWhitelist = mockWhitelist.map((row) => ({ ...row }))
const originalBlacklist = mockBlacklist.map((row) => ({ ...row }))
const originalAudit = mockAuditLogs.slice()
const server = setupServer(...quarantineHandlers)
beforeAll(() => server.listen())
afterAll(() => server.close())
afterEach(() => {
  mockQuarantine.splice(0, mockQuarantine.length, ...originalQuarantine.map((row) => ({ ...row })))
  mockWhitelist.splice(0, mockWhitelist.length, ...originalWhitelist.map((row) => ({ ...row })))
  mockBlacklist.splice(0, mockBlacklist.length, ...originalBlacklist.map((row) => ({ ...row })))
  mockAuditLogs.splice(0, mockAuditLogs.length, ...originalAudit)
})

it.each(['inactive', 'expired'] as const)('%s 화이트리스트는 중복 행 없이 사유와 복원 출처를 반영해 되살린다', async (state) => {
  const target = mockQuarantine[0]
  mockWhitelist.push({ ...originalWhitelist[0], hashValue: target.sha256, reason: '기존 사유', isActive: state === 'expired', expiresAt: '2020-01-01T00:00:00Z', createdAt: '2019-01-01T00:00:00Z' })
  const result = await restoreQuarantineFile(target.quarantineId, '오탐 검토 완료')
  const rows = mockWhitelist.filter((row) => row.hashValue === target.sha256)
  expect(rows).toHaveLength(1)
  expect(rows[0]).toMatchObject({ isActive: true, reason: '오탐 검토 완료', origin: 'RESTORED', originQuarantineId: target.quarantineId, expiresAt: null, createdAt: '2019-01-01T00:00:00Z' })
  expect(target).toMatchObject({ status: 'RESTORED', restoredAt: result.restoredAt })
})

it.each(['DELETED', 'EXPIRED', 'RESTORED'] as const)('%s 상태 복원은 실제 서버처럼 CONFLICT이며 데이터는 변경하지 않는다', async (status) => {
  mockQuarantine[0].status = status
  await expect(restoreQuarantineFile(mockQuarantine[0].quarantineId)).rejects.toMatchObject({ status: 409, body: { code: 'CONFLICT' } })
  expect(mockQuarantine[0].status).toBe(status)
})
