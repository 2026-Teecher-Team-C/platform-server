import { apiRequest } from './client'

export type CreateEnrollmentTokenRequest = {
  expiresInMinutes?: number
}

export type CreateEnrollmentTokenResponse = {
  token: string
  expiresAt: string
}

export function createEnrollmentToken(body: CreateEnrollmentTokenRequest) {
  return apiRequest<CreateEnrollmentTokenResponse>('/api/admin/agents/enrollment-tokens', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}
