import type { findMockAccountByAccessToken } from './data/auth'
import { mockAdminAccounts } from './data/auth'

type Account = NonNullable<ReturnType<typeof findMockAccountByAccessToken>>
type StoredSession = { adminId: string; expiresAt: number }
const ACCESS_SESSIONS_KEY = 'teecher.mock.accessSessions'
const REFRESH_SESSIONS_KEY = 'teecher.mock.refreshSessions'

function restore(key: string): Map<string, StoredSession> {
  if (typeof sessionStorage === 'undefined') return new Map()
  try {
    const entries = JSON.parse(sessionStorage.getItem(key) ?? '[]') as [string, StoredSession][]
    return new Map(entries.filter(([token, session]) => typeof token === 'string' && typeof session.adminId === 'string' && session.expiresAt > Date.now()))
  } catch {
    return new Map()
  }
}

const accessSessions = restore(ACCESS_SESSIONS_KEY)
const refreshSessions = restore(REFRESH_SESSIONS_KEY)

// 계정의 비밀번호는 복사하지 않고 ID와 만료 시각만 탭 저장소에 보존해 새로고침을 지원한다.
function persist() {
  if (typeof sessionStorage === 'undefined') return
  sessionStorage.setItem(ACCESS_SESSIONS_KEY, JSON.stringify([...accessSessions]))
  sessionStorage.setItem(REFRESH_SESSIONS_KEY, JSON.stringify([...refreshSessions]))
}

// 실제 서버처럼 access와 refresh를 분리한다.
export function issueMockSession(account: Account) {
  // randomUUID는 HTTPS에서만 제공되므로 사설 HTTP 개발 주소에서도 가능한 getRandomValues를 쓴다.
  const random = () => Array.from(crypto.getRandomValues(new Uint8Array(24)), (value) => value.toString(16).padStart(2, '0')).join('')
  const accessToken = 'mock-access-' + random()
  const refreshToken = 'mock-refresh-' + random()
  accessSessions.set(accessToken, { adminId: account.adminId, expiresAt: Date.now() + 60 * 60 * 1000 })
  refreshSessions.set(refreshToken, { adminId: account.adminId, expiresAt: Date.now() + 7 * 24 * 60 * 60 * 1000 })
  persist()
  return { accessToken, refreshToken, tokenType: 'Bearer' }
}

export function findMockSession(token: string) {
  const session = accessSessions.get(token)
  const account = mockAdminAccounts.find((item) => item.adminId === session?.adminId)
  return session && session.expiresAt > Date.now() && account?.isActive ? account : null
}

export function rotateMockSession(refreshToken: string) {
  const session = refreshSessions.get(refreshToken)
  const account = mockAdminAccounts.find((item) => item.adminId === session?.adminId)
  refreshSessions.delete(refreshToken)
  persist()
  return session && session.expiresAt > Date.now() && account?.isActive ? issueMockSession(account) : null
}

export function revokeMockSession(refreshToken: string, adminId: string) {
  if (refreshSessions.get(refreshToken)?.adminId === adminId) refreshSessions.delete(refreshToken)
  persist()
}
