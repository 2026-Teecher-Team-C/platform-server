import { describe, expect, it } from 'vitest'
import type { DownloadEventListItem } from '../api/events'
import { findNewEventIds, startOfLocalDay, summarizeEvents } from './summarize'

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
    ...overrides,
  }
}

describe('summarizeEvents', () => {
  it('판정별 건수와 캐시 히트 건수를 따로 센다', () => {
    const summary = summarizeEvents([
      event({ decision: 'BLOCKED', cacheHit: true }),
      event({ decision: 'BLOCKED' }),
      event({ decision: 'FAIL_CLOSE' }),
      event({ decision: 'RELEASED', cacheHit: true }),
      event({ decision: 'BYPASSED' }),
    ])

    expect(summary).toEqual({
      total: 5,
      blocked: 2,
      failClose: 1,
      released: 1,
      bypassed: 1,
      cacheHit: 2,
    })
  })

  it('이벤트가 없으면 모두 0이다', () => {
    expect(summarizeEvents([])).toEqual({
      total: 0,
      blocked: 0,
      failClose: 0,
      released: 0,
      bypassed: 0,
      cacheHit: 0,
    })
  })
})

describe('startOfLocalDay', () => {
  it('같은 날 로컬 자정을 돌려준다', () => {
    const start = startOfLocalDay(new Date(2026, 9, 15, 14, 30, 12, 500))

    expect(start).toEqual(new Date(2026, 9, 15, 0, 0, 0, 0))
  })

  it('넘겨받은 Date를 바꾸지 않는다', () => {
    const now = new Date(2026, 9, 15, 14, 30)
    startOfLocalDay(now)

    expect(now).toEqual(new Date(2026, 9, 15, 14, 30))
  })
})

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
