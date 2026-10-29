import { useEffect, useRef, useState } from 'react'
import { getDownloadEvents } from '../lib/api/events'
import type { DownloadDecision, DownloadDecisionSource } from '../lib/api/events'
import { findNewEventIds, startOfLocalDay, summarizeEvents } from '../lib/dashboard/summarize'
import { decisionBadge } from '../lib/events/decision'
import { usePolling } from '../lib/hooks/usePolling'

const POLL_INTERVAL_MS = 5000
const REQUEST_TIMEOUT_MS = 8000
// 첫 페이지만 서버가 허용하는 최대 크기(size 200)로 받아 집계한다. 넘치면 totalElements로 알린다.
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

// 비율 막대와 범례에 쓰는 순서. 차단 계열을 앞에 둔다.
const BREAKDOWN: { key: 'blocked' | 'failClose' | 'released' | 'bypassed'; decision: DownloadDecision }[] = [
  { key: 'blocked', decision: 'BLOCKED' },
  { key: 'failClose', decision: 'FAIL_CLOSE' },
  { key: 'released', decision: 'RELEASED' },
  { key: 'bypassed', decision: 'BYPASSED' },
]

function loadTodayEvents(signal: AbortSignal) {
  return getDownloadEvents(
    {
      from: startOfLocalDay(new Date()).toISOString(),
      size: EVENT_LIMIT,
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

function formatCount(value: number, loaded: boolean) {
  return loaded ? value.toLocaleString('ko-KR') : '—'
}

export function DashboardPage() {
  const { data, error, lastUpdatedAt } = usePolling(
    loadTodayEvents,
    POLL_INTERVAL_MS,
    REQUEST_TIMEOUT_MS,
  )
  const failedBeforeFirstLoad = data === null && error !== null
  const loaded = data !== null
  const events = data?.content ?? []
  const totalToday = data?.totalElements ?? 0
  const summary = summarizeEvents(events)
  const recent = events.slice(0, RECENT_ROWS)
  const stopped = summary.blocked + summary.failClose

  // 폴링으로 새로 들어온 행만 잠깐 강조한다.
  const seenIdsRef = useRef<Set<string> | null>(null)
  const [newIds, setNewIds] = useState<Set<string>>(() => new Set())
  useEffect(() => {
    if (data === null) {
      return
    }
    setNewIds(findNewEventIds(seenIdsRef.current, data.content))
    seenIdsRef.current = new Set(data.content.map((event) => event.eventId))
  }, [data])

  const statusText = lastUpdatedAt
    ? (error !== null ? '갱신 실패 · 마지막 ' : '5초마다 갱신 · ') + formatTime(lastUpdatedAt)
    : failedBeforeFirstLoad
      ? '연결 실패'
      : '불러오는 중'

  return (
    <section className="page dashboard">
      <header className="dashboard-header">
        <div>
          <p className="eyebrow">Dashboard</p>
          <h1>대시보드</h1>
        </div>
        <p className={'live-status' + (error !== null ? ' is-stale' : '')} role="status">
          <span className="live-dot" aria-hidden="true" />
          {statusText}
        </p>
      </header>

      {error !== null && (
        <p className="dashboard-alert" role="alert">
          서버에서 이벤트를 가져오지 못했습니다.
          {lastUpdatedAt && ' 아래는 ' + formatTime(lastUpdatedAt) + '에 받은 데이터입니다.'}
        </p>
      )}

      {totalToday > events.length && (
        <p className="dashboard-note">
          오늘 이벤트 {totalToday.toLocaleString('ko-KR')}건 중 최근 {events.length}건으로만 집계했습니다.
        </p>
      )}

      <section className="verdict-summary" aria-labelledby="verdict-heading">
        <div className="verdict-headline">
          <h2 id="verdict-heading">오늘 차단</h2>
          <strong className={stopped === 0 ? 'is-zero' : undefined}>{formatCount(stopped, loaded)}</strong>
          <p>
            악성 {formatCount(summary.blocked, loaded)} · 검사 불가 {formatCount(summary.failClose, loaded)}
          </p>
        </div>

        <div className="verdict-breakdown">
          <p className="verdict-total">
            00:00부터 다운로드 <strong>{formatCount(summary.total, loaded)}</strong>건
          </p>
          <div className="verdict-bar" aria-hidden="true">
            {BREAKDOWN.map(({ key, decision }) => (
              <span
                key={key}
                className={'verdict-segment tone-' + decisionBadge(decision).tone}
                style={{ flexGrow: summary[key] }}
              />
            ))}
          </div>
          <dl className="verdict-legend">
            {BREAKDOWN.map(({ key, decision }) => (
              <div key={key}>
                <dt>
                  <span className={'verdict-dot tone-' + decisionBadge(decision).tone} aria-hidden="true" />
                  {DECISION_LABELS[decision]}
                </dt>
                <dd>{formatCount(summary[key], loaded)}</dd>
              </div>
            ))}
            <div className="verdict-legend-cache">
              <dt>캐시 적중</dt>
              <dd>{formatCount(summary.cacheHit, loaded)}</dd>
            </div>
          </dl>
        </div>
      </section>

      <section className="recent-events" aria-labelledby="recent-heading">
        <div className="recent-events-heading">
          <h2 id="recent-heading">최근 이벤트</h2>
          <span>최근 {recent.length}건</span>
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
            <table className="event-table dashboard-table">
              <thead>
                <tr>
                  <th>시간</th>
                  <th>PC</th>
                  <th>파일명</th>
                  <th className="numeric">크기</th>
                  <th>판정</th>
                  <th>판정 출처</th>
                  <th>캐시</th>
                </tr>
              </thead>
              <tbody>
                {recent.map((event) => {
                  const badge = decisionBadge(event.decision)
                  const stoppedRow = event.decision === 'BLOCKED' || event.decision === 'FAIL_CLOSE'
                  return (
                    <tr
                      key={event.eventId}
                      className={(stoppedRow ? 'is-stopped' : '') + (newIds.has(event.eventId) ? ' is-new' : '')}
                    >
                      <td className="event-time">{formatTime(event.createdAt)}</td>
                      <td>{event.hostname}</td>
                      <td className="event-filename" title={event.filename ?? event.sha256 ?? ''}>
                        {event.filename ?? (event.sha256 ? event.sha256.slice(0, 12) + '…' : '—')}
                      </td>
                      <td className="numeric">{formatBytes(event.fileSize)}</td>
                      <td>
                        <span className={'verdict-label tone-' + badge.tone}>
                          <span className={'verdict-dot tone-' + badge.tone} aria-hidden="true" />
                          {event.decision ? DECISION_LABELS[event.decision] : badge.text}
                        </span>
                      </td>
                      <td>{event.decisionSource ? SOURCE_LABELS[event.decisionSource] ?? event.decisionSource : '—'}</td>
                      <td>{event.cacheHit ? '히트' : '—'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </section>
  )
}
