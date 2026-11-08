import { describe, expect, it } from 'vitest'
import type { DownloadEventListItem } from '../api/events'
import { findNewEventIds } from './summarize'

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
