import { getDownloadEvents } from '../lib/api/events'
import type { DownloadDecision, DownloadDecisionSource } from '../lib/api/events'
import { startOfLocalDay, summarizeEvents } from '../lib/dashboard/summarize'
import type { EventSummary } from '../lib/dashboard/summarize'
import { usePolling } from '../lib/hooks/usePolling'

const POLL_INTERVAL_MS = 5000
const REQUEST_TIMEOUT_MS = 8000
// 서버가 아직 커서를 주지 않으므로(nextCursor=null) 한 번에 받을 수 있는 최대치로 집계한다.
const EVENT_LIMIT = 200
const RECENT_ROWS = 20

const DECISION_LABELS: Record<DownloadDecision, string> = {
  BLOCKED: '악성 차단',
  FAIL_CLOSE: '검사 불가 차단',
  RELEASED: '안전 통과',
  BYPASSED: '검사 생략',
}

const SOURCE_LABELS: Record<DownloadDecisionSource, string> = {
  WHITELIST: '화이트리스트',
  BLACKLIST: '블랙리스트',
  CACHE: '캐시',
  ENGINE: '탐지 엔진',
  POLICY: '정책',
}

const SUMMARY_CARDS: { key: keyof EventSummary; label: string; tone: string }[] = [
  { key: 'blocked', label: DECISION_LABELS.BLOCKED, tone: 'danger' },
  { key: 'failClose', label: DECISION_LABELS.FAIL_CLOSE, tone: 'warning' },
  { key: 'released', label: DECISION_LABELS.RELEASED, tone: 'success' },
  { key: 'bypassed', label: DECISION_LABELS.BYPASSED, tone: 'neutral' },
  { key: 'cacheHit', label: '캐시 히트', tone: 'accent' },
]

function loadTodayEvents(signal: AbortSignal) {
  return getDownloadEvents(
    {
      from: startOfLocalDay(new Date()).toISOString(),
      limit: EVENT_LIMIT,
    },
    signal,
  )
}

function formatTime(value: string | Date) {
  return new Date(value).toLocaleTimeString('en-GB', { hour12: false })
}

function formatBytes(bytes: number | null) {
  if (bytes === null) {
    return '—'
  }
  if (bytes < 1024) {
    return bytes + ' B'
  }
  if (bytes < 1024 * 1024) {
    return (bytes / 1024).toFixed(1) + ' KB'
  }
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

export function DashboardPage() {
  const { data, error, lastUpdatedAt } = usePolling(
    loadTodayEvents,
    POLL_INTERVAL_MS,
    REQUEST_TIMEOUT_MS,
  )
  const failedBeforeFirstLoad = data === null && error !== null
  const events = data?.items ?? []
  const summary = summarizeEvents(events)
  const recent = events.slice(0, RECENT_ROWS)

  return (
    <section className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Today</p>
          <h1>대시보드</h1>
          <p>오늘 00:00 이후 다운로드 판정 결과입니다. 5초마다 갱신합니다.</p>
        </div>
        <span className="phase-badge">
          {lastUpdatedAt
            ? '마지막 갱신 ' + formatTime(lastUpdatedAt)
            : failedBeforeFirstLoad
              ? '연결 실패'
              : '불러오는 중'}
        </span>
      </div>

      {error !== null && (
        <p className="dashboard-alert" role="alert">
          서버에서 이벤트를 가져오지 못했습니다.
          {lastUpdatedAt && ' 아래는 ' + formatTime(lastUpdatedAt) + '에 받은 데이터입니다.'}
        </p>
      )}

      <div className="stat-grid">
        {SUMMARY_CARDS.map((card) => (
          <div key={card.key} className={'stat-card stat-card-' + card.tone}>
            <span>{card.label}</span>
            <strong>{data ? summary[card.key].toLocaleString('ko-KR') : '—'}</strong>
          </div>
        ))}
      </div>

      {events.length >= EVENT_LIMIT && (
        <p className="dashboard-note">
          오늘 이벤트가 {EVENT_LIMIT}건을 넘어 최근 {EVENT_LIMIT}건으로만 집계했습니다.
        </p>
      )}

      <div className="dashboard-panel">
        <div className="dashboard-panel-heading">
          <h2>최근 이벤트</h2>
          <span>오늘 {summary.total.toLocaleString('ko-KR')}건 중 최근 {recent.length}건</span>
        </div>

        {recent.length === 0 ? (
          <p className="dashboard-empty">
            {data
              ? '오늘 기록된 다운로드 이벤트가 없습니다.'
              : failedBeforeFirstLoad
                ? '이벤트를 가져오지 못했습니다. 서버가 응답하면 자동으로 다시 표시합니다.'
                : '이벤트를 불러오는 중입니다.'}
          </p>
        ) : (
          <div className="table-scroll">
            <table className="event-table">
              <thead>
                <tr>
                  <th>시간</th>
                  <th>PC</th>
                  <th>파일명</th>
                  <th>크기</th>
                  <th>판정</th>
                  <th>판정 출처</th>
                  <th>캐시</th>
                </tr>
              </thead>
              <tbody>
                {recent.map((event) => (
                  <tr key={event.eventId}>
                    <td>{formatTime(event.createdAt)}</td>
                    <td>{event.hostname}</td>
                    <td className="event-filename" title={event.filename ?? event.sha256 ?? ''}>
                      {event.filename ?? (event.sha256 ? event.sha256.slice(0, 12) + '…' : '—')}
                    </td>
                    <td>{formatBytes(event.fileSize)}</td>
                    <td>
                      <span className={'decision-badge decision-' + event.decision.toLowerCase()}>
                        {DECISION_LABELS[event.decision] ?? event.decision}
                      </span>
                    </td>
                    <td>{event.decisionSource ? SOURCE_LABELS[event.decisionSource] ?? event.decisionSource : '—'}</td>
                    <td>{event.cacheHit ? '히트' : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}
