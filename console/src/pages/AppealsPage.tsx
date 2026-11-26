import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { listAppeals } from '../lib/api/adminAppeals'
import type { AdminAppealListItem } from '../lib/api/adminAppeals'
import type { AppealStatus } from '../lib/api/appeals'
import type { PageResponse } from '../lib/api/types'
import {
  APPEAL_STATUS_BADGE, APPEAL_STATUS_LABEL, appealThreatLabel, formatAppealFullTime, formatAppealTime,
} from '../lib/appealReview'

const PAGE_SIZE = 20

const FILTERS: { label: string; status: AppealStatus | undefined }[] = [
  { label: '대기', status: 'PENDING' },
  { label: '승인', status: 'APPROVED' },
  { label: '거절', status: 'REJECTED' },
  { label: '전체', status: undefined },
]

export function AppealsPage() {
  const navigate = useNavigate()
  // 담당자가 처리할 일부터 보이도록 기본은 검토 대기다.
  const [status, setStatus] = useState<AppealStatus | undefined>('PENDING')
  const [pageNumber, setPageNumber] = useState(0)
  const [refreshKey, setRefreshKey] = useState(0)
  const [page, setPage] = useState<PageResponse<AdminAppealListItem> | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState(false)

  useEffect(() => {
    let active = true
    listAppeals({ status, page: pageNumber, size: PAGE_SIZE })
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
  }, [status, pageNumber, refreshKey])

  function selectStatus(next: AppealStatus | undefined) {
    if (next === status) return
    setStatus(next)
    setPageNumber(0)
    setPage(null)
    setIsLoading(true)
  }

  function refresh() {
    setIsLoading(true)
    setRefreshKey((key) => key + 1)
  }

  function movePage(delta: number) {
    if (isLoading) return
    setPageNumber((current) => current + delta)
    setPage(null)
    setIsLoading(true)
  }

  function openDetail(appealId: string) {
    navigate('/appeals/' + encodeURIComponent(appealId))
  }

  return (
    <section className="page events-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Appeals</p>
          <h1>소명 요청</h1>
        </div>
        <button className="events-button" type="button" onClick={refresh} disabled={isLoading}>새로고침</button>
      </div>

      <div className="events-panel">
        <div className="events-panel-heading">
          <h2>소명 요청 목록</h2>
          <div className="appeal-filter" role="group" aria-label="소명 상태">
            {FILTERS.map((filter) => {
              const selected = filter.status === status
              return (
                <button key={filter.label} type="button" aria-pressed={selected} onClick={() => selectStatus(filter.status)}>
                  {filter.label}
                  {selected && page ? <span className="appeal-filter-count">{page.totalElements}</span> : null}
                </button>
              )
            })}
          </div>
        </div>
        {isLoading ? (
          <p className="events-state" role="status">소명 요청을 불러오는 중입니다.</p>
        ) : error ? (
          <div className="events-state" role="alert">
            <p>소명 요청을 불러오지 못했습니다.</p>
            <button className="events-button" type="button" onClick={refresh}>다시 시도</button>
          </div>
        ) : !page?.content.length ? (
          <p className="events-state">
            {status === 'PENDING' ? '검토 대기 중인 소명이 없습니다.' : '조건에 맞는 소명이 없습니다.'}
          </p>
        ) : (
          <div className="events-table-scroll">
            <table className="events-table appeal-table">
              <thead><tr>
                <th scope="col">접수 시각</th><th scope="col">PC</th><th scope="col">파일</th>
                <th scope="col">위협</th><th scope="col">상태</th>
              </tr></thead>
              <tbody>{page.content.map((item) => (
                <tr key={item.appealId} onClick={() => openDetail(item.appealId)}>
                  <td title={formatAppealFullTime(item.createdAt)}>{formatAppealTime(item.createdAt)}</td>
                  <td className="events-truncate" title={item.hostname ?? undefined}>{item.hostname || '—'}</td>
                  {/* 파일명은 직원 PC가 정한 값이다. 텍스트로만 보여 주고, 키보드 이동용 링크는 상세 화면으로만 간다. */}
                  <td className="events-truncate" title={item.filename ?? undefined}>
                    <Link className="events-detail-link" to={'/appeals/' + encodeURIComponent(item.appealId)}
                      onClick={(event) => event.stopPropagation()}>
                      {item.filename || '파일명 없음'}
                    </Link>
                  </td>
                  <td className="events-truncate">{appealThreatLabel(item.evidenceKind, item.threat)}</td>
                  <td><span className={APPEAL_STATUS_BADGE[item.status]}>{APPEAL_STATUS_LABEL[item.status]}</span></td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
        <div className="events-pagination">
          <span>{pageNumber + 1} / {Math.max(page?.totalPages ?? 1, 1)}페이지</span>
          <div>
            <button className="events-button" type="button" disabled={pageNumber === 0 || isLoading} onClick={() => movePage(-1)}>이전</button>
            <button className="events-button" type="button" disabled={!page || pageNumber + 1 >= page.totalPages || isLoading} onClick={() => movePage(1)}>다음</button>
          </div>
        </div>
      </div>
    </section>
  )
}
