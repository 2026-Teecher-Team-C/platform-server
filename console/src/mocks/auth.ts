import {
  findMockAccountByAccessToken,
  hasAtLeastRole,
} from './data/auth'
import type { AdminRole } from '../lib/api/auth'
import { findMockSession } from './sessions'

export function getBearerToken(request: Request) {
  const authorization = request.headers.get('authorization')

  if (!authorization?.startsWith('Bearer ')) {
    return null
  }

  return authorization.slice('Bearer '.length).trim()
}

export function getAuthenticatedMockAccount(request: Request) {
  const token = getBearerToken(request)

  if (!token) {
    return null
  }

  return findMockSession(token) ?? findMockAccountByAccessToken(token) ?? null
}

export function canAccessMockRole(request: Request, required: AdminRole) {
  const account = getAuthenticatedMockAccount(request)

  return account ? hasAtLeastRole(account.role, required) : false
}
