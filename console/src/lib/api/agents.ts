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

export type AgentStatus = 'ACTIVE' | 'INACTIVE' | 'REVOKED'
export type OsPlatform = 'WINDOWS' | 'MACOS' | 'LINUX'

export type AgentListItem = {
  agentId: string
  hostname: string
  osPlatform: OsPlatform
  agentVersion: string
  status: AgentStatus
  lastHeartbeatAt: string | null
}

export type AgentListResponse = {
  items: AgentListItem[]
  nextCursor: string | null
}

export type AgentDetail = {
  agentId: string
  device: {
    deviceId: string
    hostname: string
    osPlatform: OsPlatform
  }
  agentVersion: string
  status: AgentStatus
  lastHeartbeatAt: string | null
  totalBlockedCount: number
  createdAt: string
  updatedAt: string
}

export type AgentQuery = {
  status?: AgentStatus
  hostname?: string
  osPlatform?: OsPlatform
  cursor?: string
  limit?: number
}

export function getAgents(query: AgentQuery = {}) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value))
    }
  }
  const suffix = search.toString() ? '?' + search.toString() : ''
  return apiRequest<AgentListResponse>('/api/admin/agents' + suffix)
}

export function getAgent(agentId: string) {
  return apiRequest<AgentDetail>('/api/admin/agents/' + encodeURIComponent(agentId))
}
