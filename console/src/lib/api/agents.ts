import { apiRequest } from './client'
import type { PageResponse } from './types'

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

// 서버 목록 계약을 그대로 사용한다. page는 0부터 시작하며 하트비트는 null일 수 있다.
export type AgentListResponse = PageResponse<AgentListItem>

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
  page?: number
  size?: number
}

export function getAgents(query: AgentQuery = {}) {
  // 비어 있는 필터만 생략하고 숫자 0은 보존해 첫 페이지도 명시적으로 요청한다.
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
