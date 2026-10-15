import type { AgentDetail, AgentListItem } from '../../lib/api/agents'

export const mockAgents: AgentListItem[] = [
  {
    agentId: '10000000-0000-4000-8000-000000000001',
    hostname: 'DESKTOP-7F2K91',
    osPlatform: 'WINDOW',
    agentVersion: '0.3.1',
    status: 'ACTIVE',
    lastHeartbeatAt: '2026-10-11T17:58:12+09:00',
  },
  {
    agentId: '10000000-0000-4000-8000-000000000002',
    hostname: 'MACBOOK-DEV',
    osPlatform: 'MAC',
    agentVersion: '0.3.1',
    status: 'ACTIVE',
    lastHeartbeatAt: '2026-10-11T17:57:42+09:00',
  },
  {
    agentId: '10000000-0000-4000-8000-000000000003',
    hostname: 'LAB-LINUX-01',
    osPlatform: 'LINUX',
    agentVersion: '0.2.4',
    status: 'INACTIVE',
    lastHeartbeatAt: '2026-10-10T08:10:00+09:00',
  },
]

export function toMockAgentDetail(item: AgentListItem): AgentDetail {
  return {
    agentId: item.agentId,
    device: {
      deviceId: item.agentId.replace(/^1/, '2'),
      hostname: item.hostname,
      osPlatform: item.osPlatform,
    },
    agentVersion: item.agentVersion,
    status: item.status,
    lastHeartbeatAt: item.lastHeartbeatAt,
    totalBlockedCount: item.agentId.endsWith('1') ? 5 : 1,
    createdAt: '2026-09-01T09:00:00+09:00',
    updatedAt: '2026-10-11T17:58:12+09:00',
  }
}
