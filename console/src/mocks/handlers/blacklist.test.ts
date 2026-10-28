import { afterAll, afterEach, beforeAll, expect, it, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { hashlistHandlers } from './hashlists'
import { API_BASE_URL } from '../../lib/api/client'
import { createBlacklist, getBlacklist, removeBlacklist, updateBlacklistReason } from '../../lib/api/hashlists'
import { mockBlacklist, mockWhitelist } from '../data/hashlists'
import { mockAuditLogs } from '../data/auditLogs'

vi.mock('../http', async (importOriginal) => ({
  ...await importOriginal<typeof import('../http')>(),
  authorizeMockRequest: () => ({ account: { adminId: 'qa-admin', username: 'qa-admin', role: 'ADMIN' }, error: null }),
}))
// 인증 기능과 함께 반영해도 세션 저장소의 다른 기능을 지우지 않도록 토큰 조회만 대체한다.
vi.mock(import('../../lib/auth/tokenStore'), async (importOriginal) => ({
  ...await importOriginal(), getAccessToken: () => null,
}))
const server = setupServer(...hashlistHandlers)
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

it('대문자 해시 앞부분 검색과 page/size를 실제 서버 계약에 맞게 처리한다', async () => {
  const response = await fetch(API_BASE_URL + '/api/admin/blacklist?hashValue=9B&page=0&size=1')
  const body = await response.json()
  expect(body).toMatchObject({ page: 0, size: 1, totalElements: 1, totalPages: 1 })
  expect(body.content[0]).toMatchObject({ source: 'IMPORT', severity: 'CRITICAL', addedBy: null })
})

it('필터를 함께 적용하며 false 활성 여부를 누락하지 않는다', async () => {
  mockBlacklist.push({ ...mockBlacklist[1], hashValue: 'a'.repeat(64), isActive: false, deactivatedAt: '2026-10-12T00:00:00Z' })
  try {
    // client에서 false가 빠지면 활성 목록이 반환되므로 실제 직렬화 경로를 검증한다.
    const data = await getBlacklist({ source: 'IMPORT', severity: 'CRITICAL', isActive: false, page: 0, size: 20 })
    expect(data).toMatchObject({ totalElements: 1 })
    expect(data.content[0]).toMatchObject({ isActive: false, hashValue: 'a'.repeat(64) })
  } finally { mockBlacklist.pop() }
})

it.each(['hashValue=%25', 'severity=HIGH,LOW', 'source=OTHER', 'page=-1', 'size=201', 'page=1.5', 'isActive=other'])(
  '유효하지 않은 목록 조건 %s는 400으로 거절한다', async (query) => {
    const response = await fetch(API_BASE_URL + '/api/admin/blacklist?' + query)
    expect(response.status).toBe(400)
    expect(await response.json()).toMatchObject({ code: 'INVALID_INPUT' })
  },
)

it('페이지 범위를 벗어나면 전체 개수는 유지하고 빈 목록을 반환한다', async () => {
  const response = await fetch(API_BASE_URL + '/api/admin/blacklist?page=2&size=1')
  expect(await response.json()).toMatchObject({ content: [], totalElements: 2, totalPages: 2 })
})

it('등록 시각이 같으면 해시 내림차순으로 정렬해 페이지 경계를 고정한다', async () => {
  const common = { ...mockBlacklist[0], createdAt: '2026-10-27T00:00:00Z' }
  mockBlacklist.push({ ...common, hashValue: 'ab'.repeat(32) }, { ...common, hashValue: 'ac'.repeat(32) })
  try {
    const response = await getBlacklist({ hashValue: 'a', page: 0, size: 1 })
    expect(response.content[0].hashValue).toBe('ac'.repeat(32))
  } finally { mockBlacklist.splice(-2) }
})

it('해제했던 해시는 기존 행을 재활성화하고 등록→수정→해제 흐름을 유지한다', async () => {
  const snapshot = mockBlacklist.map((item) => ({ ...item }))
  const hash = 'd1'.repeat(32)
  mockBlacklist.push({ ...mockBlacklist[1], hashValue: hash, isActive: false, createdAt: '2000-01-01T00:00:00Z', deactivatedAt: '2001-01-01T00:00:00Z' })
  try {
    const created = await createBlacklist({ sha256: hash.toUpperCase(), reason: '다시 등록' })
    expect(created).toMatchObject({ isActive: true, severity: 'HIGH', source: 'MANUAL', deactivatedAt: null, createdAt: '2000-01-01T00:00:00Z' })
    expect(mockBlacklist.filter((item) => item.hashValue === hash)).toHaveLength(1)
    await expect(createBlacklist({ sha256: hash, reason: '중복' })).rejects.toMatchObject({ status: 409, body: { code: 'CONFLICT' } })
    expect((await updateBlacklistReason(hash, '수정된 사유')).reason).toBe('수정된 사유')
    expect(mockAuditLogs.find((log) => log.action === 'UPDATE_BLACKLIST' && log.targetId === 'SHA256:' + hash)?.detail)
      .toEqual({ previousReason: '다시 등록', newReason: '수정된 사유' })
    await removeBlacklist(hash)
    const inactive = await getBlacklist({ hashValue: hash, isActive: false })
    expect(inactive.content[0]).toMatchObject({ isActive: false, reason: '수정된 사유' })
    expect(inactive.content[0].deactivatedAt).not.toBeNull()
    await expect(updateBlacklistReason(hash, '재수정')).rejects.toMatchObject({ status: 404, body: { code: 'NOT_FOUND' } })
    await expect(removeBlacklist(hash)).rejects.toMatchObject({ status: 404 })
  } finally { mockBlacklist.splice(0, mockBlacklist.length, ...snapshot) }
})

it('화이트리스트가 만료됐으면 등록 가능하고 유효하면 충돌한다', async () => {
  const hash = 'e1'.repeat(32)
  const snapshot = mockBlacklist.map((item) => ({ ...item }))
  mockWhitelist.push({ ...mockWhitelist[0], hashValue: hash, expiresAt: '2000-01-01T00:00:00Z' })
  try {
    expect((await createBlacklist({ sha256: hash, reason: '만료된 예외' })).hashValue).toBe(hash)
    await expect(createBlacklist({ sha256: mockWhitelist[0].hashValue, reason: '충돌' })).rejects.toMatchObject({ status: 409, body: { code: 'CONFLICT' } })
  } finally { mockWhitelist.pop(); mockBlacklist.splice(0, mockBlacklist.length, ...snapshot) }
})
