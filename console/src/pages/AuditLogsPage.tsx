import { Fragment, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { getAuditLogs } from '../lib/api/auditLogs'
import type { AuditLogItem, AuditLogQuery } from '../lib/api/auditLogs'
import type { PageResponse } from '../lib/api/types'
import {
  AUDIT_ACTION_LABEL,
  AUDIT_TARGET_LABEL,
  auditErrorMessage,
  formatAuditDetail,
  validateAuditFilters,
} from '../lib/audit'

const PAGE_SIZE = 20
const COLUMN_COUNT = 6

export function AuditLogRow({ item, expanded, onToggle }: {
  item: AuditLogItem
  expanded: boolean
  onToggle: (logId: number) => void
}) {
  const detail = formatAuditDetail(item.detail)
  return (
    <Fragment>
      <tr className={item.dangerous ? 'audit-row--dangerous' : undefined}>
        <td>{new Date(item.createdAt).toLocaleString('ko-KR')}</td>
        <td>
          <div className="audit-action">
            <span>{AUDIT_ACTION_LABEL[item.action] ?? item.action}</span>
            {/* 검사를 약하게 만드는 조작은 따로 눈에 띄게 한다. 판단은 서버 dangerous 값을 따른다. */}
            {item.dangerous && <span className="events-badge events-badge--blocked">위험</span>}
          </div>
          <small className="audit-code">{item.action}</small>
        </td>
        <td>
          {item.actor ? (
            <>
              <span className="events-truncate audit-actor" title={item.actor.adminId}>{item.actor.username}</span>
              <small className="audit-code">{item.actor.role}</small>
            </>
          ) : (
            // LOGIN_FAILED처럼 주체가 확정되지 않은 행위다. 시도한 계정명은 대상 칸에 있다.
            <span className="audit-muted">확인되지 않음</span>
          )}
        </td>
        <td>
          <span>{AUDIT_TARGET_LABEL[item.targetType] ?? item.targetType}</span>
          <small className="events-truncate audit-code audit-target" title={item.targetId ?? undefined}>
            {item.targetId ?? '—'}
          </small>
        </td>
        <td>{item.ipAddress ?? '—'}</td>
        <td>
          {detail ? (
            <button className="events-button" type="button" aria-expanded={expanded} onClick={() => onToggle(item.logId)}>
              {expanded ? '상세 접기' : '상세 보기'}
            </button>
          ) : '—'}
        </td>
      </tr>
      {expanded && detail && (
        <tr className="audit-detail-row">
          {/* detail에는 공격자가 정한 파일명 등이 들어갈 수 있어 HTML로 넣지 않고 글자로만 보여준다. */}
          <td colSpan={COLUMN_COUNT}><pre className="audit-detail">{detail}</pre></td>
        </tr>
      )}
    </Fragment>
  )
}

export function AuditLogsPage() {
  const [action, setAction] = useState('')
  const [targetType, setTargetType] = useState('')
  const [actorId, setActorId] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [dangerousOnly, setDangerousOnly] = useState('false')
  const [filterError, setFilterError] = useState('')
  const [filters, setFilters] = useState<AuditLogQuery>({})
  const [pageNumber, setPageNumber] = useState(0)
  const [limit, setLimit] = useState(PAGE_SIZE)
  const [refreshKey, setRefreshKey] = useState(0)
  const [page, setPage] = useState<PageResponse<AuditLogItem> | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [expandedId, setExpandedId] = useState<number | null>(null)

  // 입력 중인 폼 값과 실제 조회 조건(filters)을 분리해 '조회'를 눌렀을 때만 재검색한다.
  useEffect(() => {
    let active = true
    getAuditLogs({ ...filters, page: pageNumber, size: limit })
      .then((result) => {
        if (active) {
          setPage(result)
          setError('')
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setPage(null)
          setError(auditErrorMessage(cause))
        }
      })
      .finally(() => {
        if (active) setIsLoading(false)
      })
    return () => { active = false }
  }, [filters, pageNumber, limit, refreshKey])

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const invalid = validateAuditFilters(actorId, from, to)
    if (invalid) {
      setFilterError(invalid)
      return
    }
    setFilterError('')
    setFilters({
      action: action || undefined,
      targetType: targetType || undefined,
      actorId: actorId.trim() || undefined,
      // datetime-local은 브라우저 로컬 시각이라 서버 Instant 형식(UTC ISO)으로 바꿔 보낸다.
      from: from ? new Date(from).toISOString() : undefined,
      to: to ? new Date(to).toISOString() : undefined,
      dangerousOnly: dangerousOnly === 'true' || undefined,
    })
    setPageNumber(0)
    setPage(null)
    setExpandedId(null)
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
    <section className="page events-page audit-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Audit log</p>
          <h1>감사 로그</h1>
          <p>관리자가 한 등록·해제·복원·발급·로그인 기록입니다. 검사를 약하게 만드는 조작은 위험으로 표시합니다.</p>
        </div>
        <button className="events-button" type="button" onClick={refresh} disabled={isLoading}>새로고침</button>
      </div>

      <form className="events-filters audit-filters" onSubmit={applyFilters}>
        <label>조작
          <select value={action} onChange={(event) => setAction(event.target.value)}>
            <option value="">전체</option>
            {Object.entries(AUDIT_ACTION_LABEL).map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </label>
        <label>대상 종류
          <select value={targetType} onChange={(event) => setTargetType(event.target.value)}>
            <option value="">전체</option>
            {Object.entries(AUDIT_TARGET_LABEL).map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </label>
        <label>위험 조작
          <select value={dangerousOnly} onChange={(event) => setDangerousOnly(event.target.value)}>
            <option value="false">전체</option>
            <option value="true">위험 조작만</option>
          </select>
        </label>
        <label>행위자 ID
          <input value={actorId} onChange={(event) => setActorId(event.target.value)} placeholder="관리자 UUID" />
        </label>
        <label>시작
          <input type="datetime-local" value={from} onChange={(event) => setFrom(event.target.value)} />
        </label>
        <label>끝
          <input type="datetime-local" value={to} onChange={(event) => setTo(event.target.value)} />
        </label>
        <button className="events-button events-button--primary" type="submit">조회</button>
        {filterError && <p className="events-filter-error" role="alert">{filterError}</p>}
      </form>

      <div className="events-panel">
        <div className="events-panel-heading">
          <h2>감사 로그 목록</h2>
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
          <p className="events-state" role="status">감사 로그를 불러오는 중입니다.</p>
        ) : error ? (
          <div className="events-state" role="alert">
            <p>{error}</p>
            <button className="events-button" type="button" onClick={refresh}>다시 시도</button>
          </div>
        ) : !page?.content.length ? (
          <p className="events-state">조건에 맞는 감사 로그가 없습니다.</p>
        ) : (
          <div className="events-table-scroll">
            <table className="events-table audit-table">
              <thead><tr>
                <th scope="col">시각</th><th scope="col">조작</th><th scope="col">행위자</th>
                <th scope="col">대상</th><th scope="col">IP</th><th scope="col">상세</th>
              </tr></thead>
              <tbody>{page.content.map((item) => (
                <AuditLogRow
                  key={item.logId}
                  item={item}
                  expanded={expandedId === item.logId}
                  onToggle={(logId) => setExpandedId((current) => (current === logId ? null : logId))}
                />
              ))}</tbody>
            </table>
          </div>
        )}
        <div className="events-pagination">
          <span>{pageNumber + 1} / {Math.max(page?.totalPages ?? 1, 1)}페이지 · 전체 {page?.totalElements ?? 0}건</span>
          <div>
            <button className="events-button" type="button" disabled={pageNumber === 0 || isLoading} onClick={previousPage}>이전</button>
            <button className="events-button" type="button" disabled={!page || pageNumber + 1 >= page.totalPages || isLoading} onClick={nextPage}>다음</button>
          </div>
        </div>
      </div>
    </section>
  )
}
