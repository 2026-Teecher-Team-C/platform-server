import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { createWhitelist, getWhitelist, removeWhitelist, updateWhitelistReason } from '../lib/api/hashlists'
import type { WhitelistItem, WhitelistOrigin, WhitelistQuery } from '../lib/api/hashlists'
import type { PageResponse } from '../lib/api/types'
import { isWhitelistExpired, toDateTimeLocal, validateWhitelistForm, whitelistErrorMessage } from '../lib/whitelist'

const PAGE_SIZE = 20
const HASH_PREFIX_PATTERN = /^[0-9a-fA-F]{1,64}$/

const ORIGIN_LABEL: Record<WhitelistOrigin, string> = {
  RESTORED: '오탐 복원',
  PREREGISTERED: '사전 등록',
}

function formatDate(value: string | null, empty: string) {
  return value ? new Date(value).toLocaleString('ko-KR') : empty
}

type RowMode = 'view' | 'editing' | 'confirming'

type RowActions = {
  onEdit: (item: WhitelistItem) => void
  onCancel: () => void
  onSave: () => void
  onReasonChange: (reason: string) => void
  onRemove: (item: WhitelistItem) => void
  onConfirmRemove: (item: WhitelistItem) => void
  onCopy: (item: WhitelistItem) => void
}

export function WhitelistRow({ item, mode, draftReason, isBusy, copied, expired, actions }: {
  item: WhitelistItem
  mode: RowMode
  draftReason: string
  isBusy: boolean
  copied: boolean
  // 만료된 항목은 활성이어도 더 이상 통과되지 않으므로 따로 표시한다.
  expired: boolean
  actions: RowActions
}) {
  return (
    <tr className={mode === 'view' ? undefined : 'whitelist-row--active'}>
      <td>{formatDate(item.createdAt, '—')}</td>
      <td>
        {/* 표에서는 앞부분만 보이므로 전체 값은 복사 버튼으로 가져간다. */}
        <div className="whitelist-hash">
          <span className="events-truncate event-hash" title={item.hashValue}>{item.hashValue}</span>
          <button className="whitelist-copy" type="button" aria-label="SHA-256 복사" onClick={() => actions.onCopy(item)}>
            {copied ? '복사됨' : '복사'}
          </button>
        </div>
      </td>
      {mode === 'editing' ? (
        <td>
          <input
            className="whitelist-reason-input"
            aria-label="수정할 사유"
            value={draftReason}
            autoFocus
            onChange={(event) => actions.onReasonChange(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') actions.onSave()
              if (event.key === 'Escape') actions.onCancel()
            }}
          />
        </td>
      ) : (
        <td className="events-truncate" title={item.reason}>{item.reason}</td>
      )}
      <td>
        {/* 복원으로 들어온 항목은 원본 격리 파일 상세에서 근거를 확인할 수 있게 연결한다. */}
        {item.originQuarantineId ? (
          <Link className="events-detail-link" to={'/quarantine/' + encodeURIComponent(item.originQuarantineId)}>
            {ORIGIN_LABEL[item.origin]}
          </Link>
        ) : ORIGIN_LABEL[item.origin]}
      </td>
      <td>{item.addedBy?.username ?? '—'}</td>
      <td>
        {formatDate(item.expiresAt, '무기한')}
        {expired && <span className="events-badge whitelist-expired">만료됨</span>}
      </td>
      <td className="whitelist-actions-cell">
        {!item.isActive ? (
          <span className="events-badge events-badge--bypassed">해제됨</span>
        ) : mode === 'editing' ? (
          <div className="whitelist-actions">
            <button className="events-button events-button--primary" type="button" disabled={isBusy} onClick={actions.onSave}>저장</button>
            <button className="events-button" type="button" disabled={isBusy} onClick={actions.onCancel}>취소</button>
          </div>
        ) : mode === 'confirming' ? (
          // 해제하면 다음 다운로드부터 다시 검사 대상이 되므로 같은 자리에서 한 번 더 확인받는다.
          <div className="whitelist-actions">
            <button className="events-button whitelist-danger--solid" type="button" disabled={isBusy} onClick={() => actions.onConfirmRemove(item)}>해제 확인</button>
            <button className="events-button" type="button" disabled={isBusy} onClick={actions.onCancel}>취소</button>
          </div>
        ) : (
          <div className="whitelist-actions">
            <button className="events-button" type="button" disabled={isBusy} onClick={() => actions.onEdit(item)}>사유 수정</button>
            <button className="events-button whitelist-danger" type="button" disabled={isBusy} onClick={() => actions.onRemove(item)}>해제</button>
          </div>
        )}
      </td>
    </tr>
  )
}

export function WhitelistPage() {
  const [hashValue, setHashValue] = useState('')
  const [origin, setOrigin] = useState<WhitelistOrigin | ''>('')
  const [isActive, setIsActive] = useState('true')
  const [includeExpired, setIncludeExpired] = useState('false')
  const [filterError, setFilterError] = useState('')
  const [filters, setFilters] = useState<WhitelistQuery>({})
  const [pageNumber, setPageNumber] = useState(0)
  const [limit, setLimit] = useState(PAGE_SIZE)
  const [refreshKey, setRefreshKey] = useState(0)
  const [page, setPage] = useState<PageResponse<WhitelistItem> | null>(null)
  // 만료 여부와 만료 시각 입력의 최솟값은 목록을 받아온 시각을 기준으로 한다.
  const [checkedAt, setCheckedAt] = useState(() => Date.now())
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState(false)

  const [newSha256, setNewSha256] = useState('')
  const [newReason, setNewReason] = useState('')
  const [newExpiresAt, setNewExpiresAt] = useState('')
  const [createError, setCreateError] = useState('')
  const [isCreating, setIsCreating] = useState(false)

  // 한 번에 한 행만 수정하거나 해제 확인 중일 수 있다.
  const [activeRow, setActiveRow] = useState<{ hash: string, mode: Exclude<RowMode, 'view'> } | null>(null)
  const [draftReason, setDraftReason] = useState('')
  const [busyHash, setBusyHash] = useState<string | null>(null)
  const [actionError, setActionError] = useState('')
  const [copiedHash, setCopiedHash] = useState<string | null>(null)

  // 입력 중인 폼 값과 실제 조회 조건(filters)을 분리해 '조회'를 눌렀을 때만 재검색한다.
  useEffect(() => {
    let active = true
    getWhitelist({ ...filters, page: pageNumber, size: limit })
      .then((result) => {
        if (active) {
          setPage(result)
          setCheckedAt(Date.now())
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
    const prefix = hashValue.trim()
    if (prefix && !HASH_PREFIX_PATTERN.test(prefix)) {
      setFilterError('SHA-256은 1~64자리 16진수로 입력해 주세요.')
      return
    }
    setFilterError('')
    setFilters({
      hashValue: prefix.toLowerCase() || undefined,
      origin: origin || undefined,
      isActive: isActive === 'true',
      includeExpired: includeExpired === 'true',
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

  async function submitCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const invalid = validateWhitelistForm(newSha256, newReason)
    if (invalid) {
      setCreateError(invalid)
      return
    }
    setIsCreating(true)
    setCreateError('')
    try {
      await createWhitelist({
        sha256: newSha256.trim().toLowerCase(),
        reason: newReason.trim(),
        // datetime-local은 브라우저 로컬 시각이라 서버 Instant 형식(UTC ISO)으로 바꿔 보낸다.
        expiresAt: newExpiresAt ? new Date(newExpiresAt).toISOString() : undefined,
      })
      setNewSha256('')
      setNewReason('')
      setNewExpiresAt('')
      setPageNumber(0)
      refresh()
    } catch (cause) {
      setCreateError(whitelistErrorMessage(cause))
    } finally {
      setIsCreating(false)
    }
  }

  function startEdit(item: WhitelistItem) {
    setActiveRow({ hash: item.hashValue, mode: 'editing' })
    setDraftReason(item.reason)
    setActionError('')
  }

  function startRemove(item: WhitelistItem) {
    setActiveRow({ hash: item.hashValue, mode: 'confirming' })
    setActionError('')
  }

  function cancelRow() {
    setActiveRow(null)
    setActionError('')
  }

  async function saveReason() {
    if (!activeRow || activeRow.mode !== 'editing' || busyHash) return
    if (!draftReason.trim()) {
      setActionError('수정할 사유를 입력해 주세요.')
      return
    }
    setBusyHash(activeRow.hash)
    try {
      await updateWhitelistReason(activeRow.hash, draftReason.trim())
      setActiveRow(null)
      setActionError('')
      refresh()
    } catch (cause) {
      setActionError(whitelistErrorMessage(cause))
    } finally {
      setBusyHash(null)
    }
  }

  async function confirmRemove(item: WhitelistItem) {
    setBusyHash(item.hashValue)
    setActionError('')
    try {
      await removeWhitelist(item.hashValue)
      setActiveRow(null)
      refresh()
    } catch (cause) {
      setActionError(whitelistErrorMessage(cause))
    } finally {
      setBusyHash(null)
    }
  }

  async function copyHash(item: WhitelistItem) {
    try {
      await navigator.clipboard.writeText(item.hashValue)
      setCopiedHash(item.hashValue)
      window.setTimeout(() => setCopiedHash((current) => (current === item.hashValue ? null : current)), 1500)
    } catch {
      setActionError('클립보드에 복사하지 못했습니다. 브라우저 권한을 확인해 주세요.')
    }
  }

  const rowActions: RowActions = {
    onEdit: startEdit,
    onCancel: cancelRow,
    onSave: saveReason,
    onReasonChange: setDraftReason,
    onRemove: startRemove,
    onConfirmRemove: confirmRemove,
    onCopy: copyHash,
  }

  return (
    <section className="page events-page whitelist-page">
      <div className="page-heading">
        <h1 className="whitelist-title">화이트리스트</h1>
      </div>

      <form className="events-filters whitelist-create" onSubmit={submitCreate} aria-labelledby="whitelist-create-title">
        <h2 className="whitelist-form-title" id="whitelist-create-title">새 해시 등록</h2>
        <label>SHA-256
          <input value={newSha256} onChange={(event) => { setNewSha256(event.target.value); setCreateError('') }} placeholder="64자리 SHA-256" />
        </label>
        <label>등록 사유
          <input value={newReason} onChange={(event) => { setNewReason(event.target.value); setCreateError('') }} placeholder="예: 사내 배포 도구" />
        </label>
        <label>만료 시각 (선택)
          <input type="datetime-local" min={toDateTimeLocal(new Date(checkedAt))} value={newExpiresAt} onChange={(event) => { setNewExpiresAt(event.target.value); setCreateError('') }} />
        </label>
        <button className="events-button events-button--primary" type="submit" disabled={isCreating}>등록</button>
        {createError && <p className="events-filter-error" role="alert">{createError}</p>}
      </form>

      <form className="events-filters whitelist-search" onSubmit={applyFilters} aria-labelledby="whitelist-search-title">
        <h2 className="whitelist-form-title" id="whitelist-search-title">검색</h2>
        <label>SHA-256
          <input value={hashValue} onChange={(event) => setHashValue(event.target.value)} placeholder="앞부분 일치" />
        </label>
        <label>출처
          <select value={origin} onChange={(event) => setOrigin(event.target.value as WhitelistOrigin | '')}>
            <option value="">전체</option>
            <option value="RESTORED">{ORIGIN_LABEL.RESTORED}</option>
            <option value="PREREGISTERED">{ORIGIN_LABEL.PREREGISTERED}</option>
          </select>
        </label>
        <label>상태
          <select value={isActive} onChange={(event) => setIsActive(event.target.value)}>
            <option value="true">활성</option>
            <option value="false">해제됨</option>
          </select>
        </label>
        <label>만료 항목
          <select value={includeExpired} onChange={(event) => setIncludeExpired(event.target.value)}>
            <option value="false">제외</option>
            <option value="true">포함</option>
          </select>
        </label>
        {/* 이 화면의 주 동작은 등록이라 조회는 보조 버튼으로 둔다. */}
        <button className="events-button" type="submit">조회</button>
        {filterError && <p className="events-filter-error" role="alert">{filterError}</p>}
      </form>

      <div className="events-panel">
        <div className="events-panel-heading">
          <div className="whitelist-panel-title">
            <h2>화이트리스트 목록</h2>
            <button
              className="whitelist-refresh"
              type="button"
              onClick={refresh}
              disabled={isLoading}
              aria-label="새로고침"
              title="새로고침"
            >
              {/* 컬러 이모지 대신 사이드바 전원 버튼처럼 글자색을 따르는 선 아이콘을 쓴다. */}
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M21 12a9 9 0 1 1-2.64-6.36" />
                <polyline points="21 3 21 9 15 9" />
              </svg>
            </button>
          </div>
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
        {actionError && <p className="events-filter-error whitelist-action-error" role="alert">{actionError}</p>}
        {isLoading ? (
          <p className="events-state" role="status">화이트리스트를 불러오는 중입니다.</p>
        ) : error ? (
          <div className="events-state" role="alert">
            <p>화이트리스트를 불러오지 못했습니다.</p>
            <button className="events-button" type="button" onClick={refresh}>다시 시도</button>
          </div>
        ) : !page?.content.length ? (
          <p className="events-state">조건에 맞는 화이트리스트 항목이 없습니다.</p>
        ) : (
          <div className="events-table-scroll">
            <table className="events-table whitelist-table">
              <thead><tr>
                <th scope="col">등록 시각</th><th scope="col">SHA-256</th><th scope="col">사유</th>
                <th scope="col">출처</th><th scope="col">등록자</th><th scope="col">만료 시각</th><th scope="col">관리</th>
              </tr></thead>
              <tbody>{page.content.map((item) => (
                <WhitelistRow
                  key={item.hashValue}
                  item={item}
                  mode={activeRow?.hash === item.hashValue ? activeRow.mode : 'view'}
                  draftReason={draftReason}
                  isBusy={busyHash !== null}
                  copied={copiedHash === item.hashValue}
                  expired={isWhitelistExpired(item, checkedAt)}
                  actions={rowActions}
                />
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
