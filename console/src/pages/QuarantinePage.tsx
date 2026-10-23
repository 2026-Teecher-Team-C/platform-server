import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { getQuarantineFiles } from '../lib/api/quarantine'
import type { QuarantineStatus, QuarantineQuery } from '../lib/api/quarantine'
import type { PageResponse } from '../lib/api/types'
import type { QuarantineListItem } from '../lib/api/quarantine'

const PAGE_SIZE = 20

function formatSize(bytes: number) {
  return `${(bytes / 1024).toLocaleString('ko-KR', { maximumFractionDigits: 1 })} KB`
}

export function QuarantinePage() {
  const [status, setStatus] = useState<QuarantineStatus | ''>('')
  const [agentId, setAgentId] = useState('')
  const [filters, setFilters] = useState<QuarantineQuery>({})
  const [pageNumber, setPageNumber] = useState(0)
  const [limit, setLimit] = useState(PAGE_SIZE)
  const [refreshKey, setRefreshKey] = useState(0)
  const [page, setPage] = useState<PageResponse<QuarantineListItem> | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState(false)

  // 입력 중인 폼 값과 실제 조회 조건(filters)을 분리해 '조회'를 눌렀을 때만 재검색한다.
  useEffect(() => {
    let active = true
    getQuarantineFiles({ ...filters, page: pageNumber, size: limit })
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
    setFilters({
      status: status || undefined,
      agentId: agentId.trim() || undefined,
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
          <p className="eyebrow">Quarantine</p>
          <h1>격리 파일</h1>
          <p>S3에 격리된 파일의 메타데이터와 상태를 확인합니다. 원본 저장 위치는 노출되지 않습니다.</p>
        </div>
        <button className="events-button" type="button" onClick={refresh} disabled={isLoading}>새로고침</button>
      </div>

      <form className="events-filters" onSubmit={applyFilters}>
        <label>상태
          <select value={status} onChange={(event) => setStatus(event.target.value as QuarantineStatus | '')}>
            <option value="">전체</option>
            <option value="QUARANTINED">QUARANTINED</option>
            <option value="RESTORED">RESTORED</option>
            <option value="DELETED">DELETED</option>
            <option value="EXPIRED">EXPIRED</option>
          </select>
        </label>
        <label>Agent ID
          <input value={agentId} onChange={(event) => setAgentId(event.target.value)} placeholder="Agent ID" />
        </label>
        <button className="events-button events-button--primary" type="submit">조회</button>
      </form>

      <div className="events-panel">
        <div className="events-panel-heading">
          <h2>격리 파일 목록</h2>
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
          <p className="events-state" role="status">격리 파일을 불러오는 중입니다.</p>
        ) : error ? (
          <div className="events-state" role="alert">
            <p>격리 파일을 불러오지 못했습니다.</p>
            <button className="events-button" type="button" onClick={refresh}>다시 시도</button>
          </div>
        ) : !page?.content.length ? (
          <p className="events-state">조건에 맞는 격리 파일이 없습니다.</p>
        ) : (
          <div className="events-table-scroll">
            <table className="events-table">
              <thead><tr>
                <th scope="col">격리 시각</th><th scope="col">파일명</th><th scope="col">PC / hostname</th>
                <th scope="col">상태</th><th scope="col">파일 크기</th><th scope="col">복원 시각</th>
              </tr></thead>
              <tbody>{page.content.map((item) => (
                <tr key={item.quarantineId}>
                  <td>{new Date(item.quarantinedAt).toLocaleString('ko-KR')}</td>
                  <td className="events-truncate" title={item.originalFilename ?? undefined}>
                    {item.originalFilename || '파일명 없음'}
                  </td>
                  <td className="events-truncate" title={item.hostname}>{item.hostname}</td>
                  <td><span className={'events-badge events-badge--' + item.status.toLowerCase()}>{item.status}</span></td>
                  <td>{formatSize(item.fileSize)}</td>
                  <td>{item.restoredAt ? new Date(item.restoredAt).toLocaleString('ko-KR') : '—'}</td>
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
