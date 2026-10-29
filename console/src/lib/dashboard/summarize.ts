import type { DownloadEventListItem } from '../api/events'

export type EventSummary = {
  total: number
  blocked: number
  failClose: number
  released: number
  bypassed: number
  cacheHit: number
}

export function summarizeEvents(events: DownloadEventListItem[]): EventSummary {
  const summary: EventSummary = {
    total: events.length,
    blocked: 0,
    failClose: 0,
    released: 0,
    bypassed: 0,
    cacheHit: 0,
  }

  for (const event of events) {
    switch (event.decision) {
      case 'BLOCKED':
        summary.blocked += 1
        break
      case 'FAIL_CLOSE':
        summary.failClose += 1
        break
      case 'RELEASED':
        summary.released += 1
        break
      case 'BYPASSED':
        summary.bypassed += 1
        break
    }

    if (event.cacheHit) {
      summary.cacheHit += 1
    }
  }

  return summary
}

export function startOfLocalDay(now: Date): Date {
  return new Date(now.getFullYear(), now.getMonth(), now.getDate())
}

// 폴링 사이에 새로 들어온 이벤트를 고른다. 첫 응답(previous가 null)은 전부 기존 이벤트로 본다.
export function findNewEventIds(
  previous: ReadonlySet<string> | null,
  events: DownloadEventListItem[],
): Set<string> {
  if (previous === null) {
    return new Set()
  }
  return new Set(events.map((event) => event.eventId).filter((id) => !previous.has(id)))
}
