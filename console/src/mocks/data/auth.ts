import type { AdminRole, CurrentAdmin } from '../../lib/api/auth'

type MockAdminAccount = CurrentAdmin & {
  password: string
}

const initialLastLoginAt = '2026-10-11T18:00:00+09:00'

export const mockAdminAccounts: MockAdminAccount[] = [
  {
    adminId: '00000000-0000-4000-8000-000000000001',
    username: 'admin',
    password: 'password',
    role: 'ADMIN',
    isActive: true,
    lastLoginAt: initialLastLoginAt,
  },
  {
    adminId: '00000000-0000-4000-8000-000000000002',
    username: 'analyst',
    password: 'password',
    role: 'ANALYST',
    isActive: true,
    lastLoginAt: initialLastLoginAt,
  },
  {
    adminId: '00000000-0000-4000-8000-000000000003',
    username: 'viewer',
    password: 'password',
    role: 'VIEWER',
    isActive: true,
    lastLoginAt: initialLastLoginAt,
  },
]

export function findMockAccountByCredentials(username: string, password: string) {
  return mockAdminAccounts.find(
    (account) =>
      account.username === username &&
      account.password === password &&
      account.isActive,
  )
}

export function findMockAccountById(adminId: string) {
  return mockAdminAccounts.find(
    (account) => account.adminId === adminId && account.isActive,
  )
}

export function toCurrentAdmin(account: MockAdminAccount): CurrentAdmin {
  const { adminId, username, role, isActive, lastLoginAt } = account

  return {
    adminId,
    username,
    role,
    isActive,
    lastLoginAt,
  }
}

export function hasAtLeastRole(actual: AdminRole, required: AdminRole) {
  const rank: Record<AdminRole, number> = {
    VIEWER: 1,
    ANALYST: 2,
    ADMIN: 3,
  }

  return rank[actual] >= rank[required]
}
