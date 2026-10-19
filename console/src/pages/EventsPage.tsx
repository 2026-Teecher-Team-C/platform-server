import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { getDownloadEvents } from '../lib/api/events'
import type { DownloadDecision, DownloadEventQuery } from '../lib/api/events'
import type { PageResponse } from '../lib/api/types'
import type { DownloadEventListItem } from '../lib/api/events'

const PAGE_SIZE = 20


function formatSize(bytes: number | null) {
  return bytes === null ? '—' : `${(bytes / 1024).toLocaleString('ko-KR', { maximumFractionDigits: 1 })} KB`
}

export function EventsPage() {
  const [decision, setDecision] = useState<DownloadDecision | ''>('')
  const [agentId, setAgentId] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [filterError, setFilterError] = useState('')
  const [filters, setFilters] = useState<DownloadEventQuery>({})
  const [pageNumber, setPageNumber] = useState(0)
  const [limit, setLimit] = useState(PAGE_SIZE)
  const [refreshKey, setRefreshKey] = useState(0)
  const [page, setPage] = useState<PageResponse<DownloadEventListItem> | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState(false)

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
    const fromDate = from ? new Date(from) : null
    const toDate = to ? new Date(to) : null
    if ((fromDate && Number.isNaN(fromDate.getTime())) || (toDate && Number.isNaN(toDate.getTime())) ||
        (fromDate && toDate && fromDate >= toDate)) {
      setFilterError('종료 시각은 시작 시각보다 늦어야 합니다.')
      return
    }
    setFilterError('')
    setFilters({
      decision: decision || undefined,
      agentId: agentId.trim() || undefined,
      from: fromDate?.toISOString(),
      to: toDate?.toISOString(),
    })
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
          <p>다운로드 이벤트의 판정 결과를 필터링해 확인합니다.</p>
        </div>
        <button className="events-button" type="button" onClick={refresh} disabled={isLoading}>새로고침</button>
      </div>

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
                <th scope="col">판정</th><th scope="col">판정 소스</th><th scope="col">캐시</th>
                <th scope="col">파일 크기</th><th scope="col">처리 시간</th>
              </tr></thead>
              <tbody>{page.content.map((item) => (
                <tr key={item.eventId}>
                  <td>{new Date(item.createdAt).toLocaleString('ko-KR')}</td>
                  <td className="events-truncate" title={item.filename ?? undefined}>{item.filename || '파일명 없음'}</td>
                  <td className="events-truncate" title={item.hostname}>{item.hostname}</td>
                  <td><span className={'events-badge events-badge--' + item.decision.toLowerCase()}>{item.decision}</span></td>
                  <td>{item.decisionSource ?? '—'}</td>
                  <td>{item.cacheHit ? 'Hit' : 'Miss'}</td>
                  <td>{formatSize(item.fileSize)}</td>
                  <td>{item.holdDurationMs === null ? '—' : `${item.holdDurationMs.toLocaleString()} ms`}</td>
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
