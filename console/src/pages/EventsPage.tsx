import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getDownloadEvents } from '../lib/api/events'
import type { DownloadDecision, DownloadEventQuery } from '../lib/api/events'
import type { Severity } from '../lib/api/dashboard'
import { SEVERITY_LABEL, severityTone } from '../lib/dashboard/format'
import type { PageResponse } from '../lib/api/types'
import type { DownloadEventListItem } from '../lib/api/events'
import { cacheHitLabel, decisionBadge } from '../lib/events/decision'

const PAGE_SIZE = 20
const DECISIONS: DownloadDecision[] = ['RELEASED', 'BLOCKED', 'BYPASSED', 'FAIL_CLOSE']
const SEVERITIES = Object.keys(SEVERITY_LABEL) as Severity[]
const DECISION_LABEL: Record<DownloadDecision, string> = {
  BLOCKED: '차단', FAIL_CLOSE: '장애 차단', RELEASED: '통과', BYPASSED: '검사 생략',
}

// 대시보드 링크의 쿼리에서 서버가 받는 값만 골라낸다. 알 수 없는 값은 보내지 않는다(서버는 400).
function parseUrlFilters(params: URLSearchParams): DownloadEventQuery {
  const decisions = params.getAll('decision').filter((value): value is DownloadDecision =>
    (DECISIONS as string[]).includes(value))
  const minSeverity = params.get('minSeverity')
  const acknowledged = params.get('acknowledged')
  const query: DownloadEventQuery = {}
  if (decisions.length === 1) query.decision = decisions[0]
  else if (decisions.length > 1) query.decision = decisions
  if (minSeverity && (SEVERITIES as string[]).includes(minSeverity)) query.minSeverity = minSeverity as Severity
  if (acknowledged === 'true' || acknowledged === 'false') query.acknowledged = acknowledged === 'true'
  return query
}

function describeFilters(query: DownloadEventQuery): string {
  const parts: string[] = []
  if (query.decision !== undefined) {
    parts.push([query.decision].flat().map((value) => DECISION_LABEL[value]).join('·'))
  }
  if (query.minSeverity) parts.push(`${SEVERITY_LABEL[query.minSeverity]} 이상`)
  if (query.acknowledged !== undefined) parts.push(query.acknowledged ? '확인됨' : '미확인')
  return parts.join(' · ')
}

function singleDecision(query: DownloadEventQuery): DownloadDecision | '' {
  return typeof query.decision === 'string' ? query.decision : ''
}


function formatSize(bytes: number | null) {
  // 파일 크기를 알 수 없는 이벤트는 0 KB로 오해하지 않도록 빈 값으로 보여준다.
  return bytes === null ? '—' : `${(bytes / 1024).toLocaleString('ko-KR', { maximumFractionDigits: 1 })} KB`
}

export function EventsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const urlKey = searchParams.toString()
  const urlFilters = parseUrlFilters(searchParams)
  const [decision, setDecision] = useState<DownloadDecision | ''>(() => singleDecision(urlFilters))
  const [agentId, setAgentId] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [filterError, setFilterError] = useState('')
  const [filters, setFilters] = useState<DownloadEventQuery>(urlFilters)
  const [pageNumber, setPageNumber] = useState(0)
  const [limit, setLimit] = useState(PAGE_SIZE)
  const [refreshKey, setRefreshKey] = useState(0)
  const [page, setPage] = useState<PageResponse<DownloadEventListItem> | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState(false)
  const [appliedUrlKey, setAppliedUrlKey] = useState(urlKey)

  // 마운트된 뒤 URL 쿼리가 바뀌면(브라우저 히스토리 등) 다시 읽어 처음부터 조회한다.
  if (urlKey !== appliedUrlKey) {
    setAppliedUrlKey(urlKey)
    setFilters(urlFilters)
    setDecision(singleDecision(urlFilters))
    setPageNumber(0)
    setPage(null)
    setIsLoading(true)
  }
  const urlFilterText = describeFilters(urlFilters)

  function clearUrlFilters() {
    setSearchParams({}, { replace: true })
  }

  // 입력 중인 폼 값과 실제 조회 조건(filters)을 분리해 '조회'를 눌렀을 때만 재검색한다.
  // 페이지·크기·재시도 키가 바뀌면 다시 요청하고, 이전 요청의 늦은 응답은 무시한다.
  useEffect(() => {
    let active = true
    getDownloadEvents({ ...filters, page: pageNumber, size: limit })
      .then((result) => {
        if (active) {
          setPage(result)
          setError(false)
        }
      })
      .catch(() => {
        if (active) {
          setPage(null)
          setError(true)
        }
      })
      .finally(() => {
        if (active) setIsLoading(false)
      })
    return () => { active = false }
  }, [filters, pageNumber, limit, refreshKey])

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    // 종료 시각은 미포함 경계이므로 시작 시각보다 반드시 늦어야 한다.
    const fromDate = from ? new Date(from) : null
    const toDate = to ? new Date(to) : null
    if ((fromDate && Number.isNaN(fromDate.getTime())) || (toDate && Number.isNaN(toDate.getTime())) ||
        (fromDate && toDate && fromDate >= toDate)) {
      setFilterError('종료 시각은 시작 시각보다 늦어야 합니다.')
      return
    }
    setFilterError('')
    // 폼으로 조회하면 대시보드에서 건 URL 조건은 버리고 폼 값만 쓴다.
    if (urlKey) {
      setAppliedUrlKey('')
      setSearchParams({}, { replace: true })
    }
    setFilters({
      decision: decision || undefined,
      agentId: agentId.trim() || undefined,
      from: fromDate?.toISOString(),
      to: toDate?.toISOString(),
    })
    // 조건이 달라지면 이전 페이지 번호와 결과가 새 검색에 섞이지 않게 초기화한다.
    setPageNumber(0)
    setPage(null)
    setIsLoading(true)
    setRefreshKey((key) => key + 1)
  }

  function refresh() {
    setIsLoading(true)
    setRefreshKey((key) => key + 1)
  }

  function nextPage() {
    // 목록 API의 PageResponse.totalPages를 사용한다. 마지막 페이지 이후로는 요청하지 않는다.
    if (!page || pageNumber + 1 >= page.totalPages || isLoading) return
    setPageNumber((current) => current + 1)
    setPage(null)
    setIsLoading(true)
  }

  function previousPage() {
    if (pageNumber === 0 || isLoading) return
    setPageNumber((current) => current - 1)
    setPage(null)
    setIsLoading(true)
  }

  return (
    <section className="page events-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Download events</p>
          <h1>다운로드 이벤트</h1>
        </div>
        <button className="events-button" type="button" onClick={refresh} disabled={isLoading}>새로고침</button>
      </div>

      {urlFilterText && (
        <p className="events-url-filters">
          대시보드에서 건 조건: {urlFilterText}{' '}
          <button className="events-button" type="button" onClick={clearUrlFilters}>조건 해제</button>
        </p>
      )}

      <form className="events-filters" onSubmit={applyFilters}>
        <label>판정
          <select value={decision} onChange={(event) => setDecision(event.target.value as DownloadDecision | '')}>
            <option value="">전체</option>
            <option value="RELEASED">RELEASED</option>
            <option value="BLOCKED">BLOCKED</option>
            <option value="BYPASSED">BYPASSED</option>
            <option value="FAIL_CLOSE">FAIL_CLOSE</option>
          </select>
        </label>
        <label>Agent ID
          <input value={agentId} onChange={(event) => setAgentId(event.target.value)} placeholder="Agent ID" />
        </label>
        <label>시작 시각 (포함)
          <input type="datetime-local" value={from} onChange={(event) => setFrom(event.target.value)} />
        </label>
        <label>종료 시각 (미포함)
          <input type="datetime-local" value={to} onChange={(event) => setTo(event.target.value)} />
        </label>
        <button className="events-button events-button--primary" type="submit">조회</button>
        {filterError && <p className="events-filter-error" role="alert">{filterError}</p>}
      </form>

      <div className="events-panel">
        <div className="events-panel-heading">
          <h2>이벤트 목록</h2>
          <label>페이지 크기
            <select value={limit} onChange={(event) => {
              setLimit(Number(event.target.value))
              setPageNumber(0)
              setPage(null)
              setIsLoading(true)
            }}>
              <option value="20">20건</option>
              <option value="50">50건</option>
              <option value="100">100건</option>
            </select>
          </label>
        </div>
        {isLoading ? (
          <p className="events-state" role="status">이벤트를 불러오는 중입니다.</p>
        ) : error ? (
          <div className="events-state" role="alert">
            <p>이벤트를 불러오지 못했습니다.</p>
            <button className="events-button" type="button" onClick={refresh}>다시 시도</button>
          </div>
        ) : !page?.content.length ? (
          <p className="events-state">조건에 맞는 다운로드 이벤트가 없습니다.</p>
        ) : (
          <div className="events-table-scroll">
            <table className="events-table">
              <thead><tr>
                <th scope="col">발생 시각</th><th scope="col">파일명</th><th scope="col">PC / hostname</th>
                <th scope="col">판정</th><th scope="col">심각도</th><th scope="col">판정 소스</th><th scope="col">캐시</th>
                <th scope="col">파일 크기</th><th scope="col">처리 시간</th><th scope="col">상세</th>
              </tr></thead>
              <tbody>{page.content.map((item) => (
                <tr key={item.eventId}>
                  <td>{new Date(item.createdAt).toLocaleString('ko-KR')}</td>
                  <td className="events-truncate" title={item.filename ?? undefined}>{item.filename || '파일명 없음'}</td>
                  <td className="events-truncate" title={item.hostname}>{item.hostname}</td>
                  <td><span className={'events-badge events-badge--' + decisionBadge(item.decision).tone}>{decisionBadge(item.decision).text}</span></td>
                  <td>{item.severity
                    ? <span className={'severity-badge severity-badge--' + severityTone(item.severity)}>{SEVERITY_LABEL[item.severity]}</span>
                    : '—'}</td>
                  <td>{item.decisionSource ?? '—'}</td>
                  <td>{cacheHitLabel(item.decision, item.cacheHit)}</td>
                  <td>{formatSize(item.fileSize)}</td>
                  <td>{item.holdDurationMs === null ? '—' : `${item.holdDurationMs.toLocaleString()} ms`}</td>
                  {/* ID를 경로에 안전하게 넣어 선택한 이벤트의 상세 화면으로 이동한다. */}
                  <td><Link className="events-detail-link" to={'/events/' + encodeURIComponent(item.eventId)}>상세 보기</Link></td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
        <div className="events-pagination">
          <span>{pageNumber + 1} / {Math.max(page?.totalPages ?? 1, 1)}페이지</span>
          <div>
            <button className="events-button" type="button" disabled={pageNumber === 0 || isLoading} onClick={previousPage}>이전</button>
            <button className="events-button" type="button" disabled={!page || pageNumber + 1 >= page.totalPages || isLoading} onClick={nextPage}>다음</button>
          </div>
        </div>
      </div>
    </section>
  )
}
