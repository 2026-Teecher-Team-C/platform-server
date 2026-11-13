import { describe, expect, it } from 'vitest'
import type { DownloadEventListItem } from '../api/events'
import type { ThreatRow } from '../api/dashboard'
import { findNewEventIds, topRules } from './summarize'

function event(overrides: Partial<DownloadEventListItem>): DownloadEventListItem {
  return {
    eventId: 'e',
    agentId: 'a',
    hostname: 'pc-1',
    sha256: '0'.repeat(64),
    filename: 'file.bin',
    mimeType: null,
    fileSize: null,
    decision: 'RELEASED',
    decisionSource: 'ENGINE',
    cacheHit: false,
    holdDurationMs: null,
    createdAt: '2026-10-15T00:00:00Z',
    similarityClassification: null,
    severity: null,
    topRuleName: null,
    extraRuleCount: 0,
    acknowledged: false,
    ...overrides,
  }
}

describe('findNewEventIds', () => {
  it('첫 응답은 새 이벤트로 표시하지 않는다', () => {
    expect(findNewEventIds(null, [event({ eventId: 'a' })])).toEqual(new Set())
  })

  it('이전 응답에 없던 eventId만 고른다', () => {
    const previous = new Set(['a', 'b'])
    const events = [event({ eventId: 'c' }), event({ eventId: 'a' }), event({ eventId: 'b' })]

    expect(findNewEventIds(previous, events)).toEqual(new Set(['c']))
  })
})

describe('topRules', () => {
  const threat = (over: Partial<ThreatRow>): ThreatRow => ({
    kind: 'RULE', ruleName: 'r', severity: 'HIGH', affectedAgents: 1, blockCount: 1,
    firstSeenAt: '2026-11-01T00:00:00Z', lastSeenAt: '2026-11-02T00:00:00Z', ...over,
  })

  it('블랙리스트 줄은 빼고 차단 수가 많은 룰부터 n개를 고른다', () => {
    const rows = [
      threat({ ruleName: 'A', blockCount: 2 }),
      threat({ kind: 'BLACKLIST', ruleName: null, blockCount: 9 }),
      threat({ ruleName: 'B', blockCount: 5 }),
      threat({ ruleName: 'C', blockCount: 3 }),
    ]
    expect(topRules(rows, 2).map((r) => r.ruleName)).toEqual(['B', 'C'])
  })

  it('차단 수가 같으면 룰 이름순이고 이름 없는 룰은 뺀다', () => {
    const rows = [
      threat({ ruleName: 'Zeta', blockCount: 4 }),
      threat({ ruleName: null, blockCount: 7 }),
      threat({ ruleName: 'Alpha', blockCount: 4 }),
    ]
    expect(topRules(rows, 5).map((r) => r.ruleName)).toEqual(['Alpha', 'Zeta'])
  })

  it('룰이 없으면 빈 목록이다', () => {
    expect(topRules([], 5)).toEqual([])
  })
})
