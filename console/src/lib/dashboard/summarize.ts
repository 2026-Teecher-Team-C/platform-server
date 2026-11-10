import type { ThreatRow } from '../api/dashboard'
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

// 위협별 현황 줄에서 룰만 골라 차단 수 순으로 n개를 고른다(매칭 룰 Top 5).
// 블랙리스트 줄과 이름 없는 룰은 뺀다. 차단 수가 같으면 이름순으로 둬서 폴링마다 순서가 흔들리지 않게 한다.
export function topRules(rows: readonly ThreatRow[], n: number): ThreatRow[] {
  return rows
    .filter((row) => row.kind === 'RULE' && row.ruleName !== null)
    .sort((a, b) => b.blockCount - a.blockCount || (a.ruleName ?? '').localeCompare(b.ruleName ?? ''))
    .slice(0, n)
}
