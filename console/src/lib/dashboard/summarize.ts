import type { DownloadEventListItem } from '../api/events'

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
