import { Link } from 'react-router-dom'
import { useCallback, useEffect, useRef, useState } from 'react'
import { CounterRow } from '../components/dashboard/CounterRow'
import { DailyBlocksChart } from '../components/dashboard/DailyBlocksChart'
import { RecentBlocks } from '../components/dashboard/RecentBlocks'
import { ThreatTable } from '../components/dashboard/ThreatTable'
import { TopRules } from '../components/dashboard/TopRules'
import { TopSources } from '../components/dashboard/TopSources'
import { UnacknowledgedBell } from '../components/dashboard/UnacknowledgedBell'
import { useAuth } from '../app/useAuth'
import { ApiError } from '../lib/api/client'
import type { StackBy } from '../lib/api/dashboard'
import { PERIOD_OPTIONS, type PeriodKey } from '../lib/dashboard/format'
import { canManage } from '../lib/auth/roles'
import { loadDashboard, type DashboardSnapshot } from '../lib/dashboard/load'
import { findNewEventIds } from '../lib/dashboard/summarize'
import { usePolling } from '../lib/hooks/usePolling'

const POLL_INTERVAL_MS = 5000
const REQUEST_TIMEOUT_MS = 8000

// usePolling은 load가 바뀌어도 이전 data·error를 지우지 않는다.
// 응답과 실패에 어느 요청 조건에서 나왔는지 key를 붙여 두고, 지금 key와 다르면 없는 것으로 본다.
type KeyedSnapshot = { key: symbol; snapshot: DashboardSnapshot }

class KeyedLoadError extends Error {
  readonly key: symbol
  constructor(key: symbol, cause: unknown) {
    super('대시보드를 불러오지 못했습니다.', { cause })
    this.key = key
  }
}

type Selection = { period: PeriodKey; stackBy: StackBy; key: symbol }

function newSelection(period: PeriodKey, stackBy: StackBy): Selection {
  return { period, stackBy, key: Symbol(`${period}|${stackBy}`) }
}

const NO_IDS: ReadonlySet<string> = new Set()

function formatTime(value: Date) {
  return value.toLocaleTimeString('en-GB', { hour12: false })
}

export function DashboardPage() {
  const { user } = useAuth()
  // 기간·쌓기 기준을 바꿀 때마다 이벤트 처리기에서 새 key를 만든다. 같은 기간으로 되돌아와도 예전 응답과 섞이지 않는다.
  const [selection, setSelection] = useState<Selection>(() => newSelection('7d', 'SOURCE'))
  const { period, stackBy, key } = selection
  const changePeriod = (next: PeriodKey) =>
    setSelection((prev) => (prev.period === next ? prev : newSelection(next, prev.stackBy)))
  const changeStackBy = (next: StackBy) =>
    setSelection((prev) => (prev.stackBy === next ? prev : newSelection(prev.period, next)))

  const load = useCallback(
    async (signal: AbortSignal): Promise<KeyedSnapshot> => {
      try {
        return { key, snapshot: await loadDashboard({ period, stackBy }, signal) }
      } catch (error) {
        throw new KeyedLoadError(key, error)
      }
    },
    [period, stackBy, key],
  )
  const polling = usePolling(load, POLL_INTERVAL_MS, REQUEST_TIMEOUT_MS)

  const current = polling.data?.key === key ? polling.data : null
  const snapshot = current?.snapshot ?? null
  const error = polling.error instanceof KeyedLoadError && polling.error.key === key ? polling.error : null
  const lastUpdatedAt = current ? polling.lastUpdatedAt : null
  const stale = error !== null
  const failedBeforeFirstLoad = snapshot === null && error !== null
  // 서버에 대시보드 API가 아직 배포되지 않은 경우(404). 연결 실패와 구분해 안내한다.
  const apiMissing = failedBeforeFirstLoad && error.cause instanceof ApiError && error.cause.status === 404

  // 폴링으로 새로 들어온 최근 차단만 잠깐 강조한다. 조건이 바뀐 뒤 첫 응답은 전부 기존 행으로 본다.
  const seenRef = useRef<{ key: symbol; ids: Set<string> } | null>(null)
  const [highlight, setHighlight] = useState<{ key: symbol; ids: Set<string> } | null>(null)
  useEffect(() => {
    if (current === null) {
      return
    }
    const rows = current.snapshot.recentBlocks
    const previous = seenRef.current?.key === current.key ? seenRef.current.ids : null
    setHighlight({ key: current.key, ids: findNewEventIds(previous, rows) })
    seenRef.current = { key: current.key, ids: new Set(rows.map((event) => event.eventId)) }
  }, [current])
  const newIds = highlight !== null && highlight.key === key ? highlight.ids : NO_IDS

  const statusText = lastUpdatedAt
    ? (stale ? '갱신 실패 · 마지막 ' : '5초마다 갱신 · ') + formatTime(lastUpdatedAt)
    : apiMissing
      ? '대시보드 API 없음'
      : failedBeforeFirstLoad
        ? '연결 실패'
        : '불러오는 중'

  // 폴링마다 다시 그려지므로 "N분째"·"N분 전"이 따라 갱신된다.
  const now = new Date()

  return (
    <section className="page dashboard">
      <header className="dashboard-header">
        <div>
          <p className="eyebrow">Dashboard</p>
          <h1>위협 대시보드</h1>
        </div>
        <div className="dashboard-header__controls">
          <p className={'live-status' + (stale ? ' is-stale' : '')} role="status">
            <span className="live-dot" aria-hidden="true" />
            {statusText}
          </p>
          {!apiMissing && (
            <UnacknowledgedBell
              page={snapshot?.unacknowledged ?? null}
              loaded={snapshot !== null}
              canAcknowledge={canManage(user?.role)}
            />
          )}
          <label className="dash-period">
            기간
            <select value={period} onChange={(event) => changePeriod(event.target.value as PeriodKey)}>
              {PERIOD_OPTIONS.map((option) => (
                <option key={option.key} value={option.key}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </header>

      {apiMissing && (
        <div className="dashboard-notice">
          <h2>위협 대시보드 API가 서버에 아직 없습니다</h2>
          <p>
            서버에 대시보드 API(/api/admin/dashboard/*)가 배포되면 이 화면이 자동으로 채워집니다. 다운로드 이벤트는{' '}
            <Link to="/events">탐지 이벤트</Link> 화면에서 볼 수 있습니다.
          </p>
        </div>
      )}

      {error !== null && !apiMissing && (
        <p className="dashboard-alert" role="alert">
          서버에서 데이터를 가져오지 못했습니다.
          {lastUpdatedAt && ' 아래는 ' + formatTime(lastUpdatedAt) + '에 받은 데이터입니다.'}
        </p>
      )}

      {!apiMissing && (
        <div className="dash-stack">
          <CounterRow summary={snapshot?.summary ?? null} period={period} />
          <div className="dash-row dash-row--threats">
            <ThreatTable rows={snapshot?.threats ?? null} engine={snapshot?.engine ?? null} stale={stale} now={now} />
            <DailyBlocksChart data={snapshot?.daily ?? null} stackBy={stackBy} onStackByChange={changeStackBy} />
          </div>
          <div className="dash-row dash-row--halves">
            <TopRules rows={snapshot?.threats ?? null} />
            <TopSources agents={snapshot?.topAgents ?? null} hosts={snapshot?.topHosts ?? null} />
          </div>
          <div className="dash-row dash-row--full">
            <RecentBlocks rows={snapshot?.recentBlocks ?? null} newIds={newIds} />
          </div>
        </div>
      )}
    </section>
  )
}
