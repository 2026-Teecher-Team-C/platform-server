import { afterAll, afterEach, beforeAll, expect, it, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { appealHandlers } from './appeals'
import { approveAppeal, getAdminAppeal, listAppeals, rejectAppeal } from '../../lib/api/adminAppeals'
import { mockAppeals } from '../data/appeals'
import { mockBlacklist, mockWhitelist } from '../data/hashlists'
import { mockAuditLogs } from '../data/auditLogs'

// 목 모드의 소명 검토 계약을 검사한다. 실제 로그인/역할 검증을 대신하는 테스트는 아니다.
vi.mock('../http', async (original) => ({ ...await original<typeof import('../http')>(), authorizeMockRequest: () => ({ error: null, account: { adminId: 'qa-admin', username: 'qa', role: 'ADMIN' } }) }))
const originalAppeals = mockAppeals.map((row) => ({ ...row }))
const originalWhitelist = mockWhitelist.map((row) => ({ ...row }))
const originalBlacklist = mockBlacklist.map((row) => ({ ...row }))
const originalAudit = mockAuditLogs.slice()
const server = setupServer(...appealHandlers)
beforeAll(() => server.listen())
afterAll(() => server.close())
afterEach(() => {
  mockAppeals.splice(0, mockAppeals.length, ...originalAppeals.map((row) => ({ ...row })))
  mockWhitelist.splice(0, mockWhitelist.length, ...originalWhitelist.map((row) => ({ ...row })))
  mockBlacklist.splice(0, mockBlacklist.length, ...originalBlacklist.map((row) => ({ ...row })))
  mockAuditLogs.splice(0, mockAuditLogs.length, ...originalAudit)
})

const blacklisted = () => mockAppeals.find((appeal) => appeal.evidenceKind === 'BLACKLIST')!

it('대기 건수는 PENDING 목록의 totalElements다', async () => {
  const page = await listAppeals({ status: 'PENDING', size: 1 })
  expect(page.totalElements).toBe(mockAppeals.filter((appeal) => appeal.status === 'PENDING').length)
  expect(page.content).toHaveLength(1)
})

it('승인하면 블랙리스트를 해제하고 소명 출처로 화이트리스트에 등록한다', async () => {
  const target = blacklisted()
  expect((await getAdminAppeal(target.appealId)).blacklisted).toBe(true)
  const result = await approveAppeal(target.appealId)
  expect(result).toMatchObject({ status: 'APPROVED', decidedBy: 'qa', decisionNote: null, blacklisted: false })
  expect(mockWhitelist.find((row) => row.hashValue === target.sha256)).toMatchObject({ origin: 'APPEAL', originAppealId: target.appealId, reason: '소명 승인', isActive: true })
  expect(mockAuditLogs[0]).toMatchObject({ action: 'APPROVE_APPEAL', targetType: 'APPEAL', targetId: target.appealId })
  await expect(approveAppeal(target.appealId)).rejects.toMatchObject({ status: 409, body: { code: 'CONFLICT' } })
})

it('거절 의견이 비면 400이고 상태는 바뀌지 않는다', async () => {
  const target = blacklisted()
  await expect(rejectAppeal(target.appealId, '   ')).rejects.toMatchObject({ status: 400, body: { code: 'INVALID_INPUT' } })
  expect(target.status).toBe('PENDING')
  await expect(rejectAppeal(target.appealId, '업무용이 아닙니다.')).resolves.toMatchObject({ status: 'REJECTED', decisionNote: '업무용이 아닙니다.' })
})
