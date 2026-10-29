import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { createBlacklist, getBlacklist, removeBlacklist, updateBlacklistReason } from '../lib/api/hashlists'
import type { BlacklistItem, BlacklistQuery, BlacklistSeverity, BlacklistSource } from '../lib/api/hashlists'
import type { PageResponse } from '../lib/api/types'
import { blacklistErrorMessage, getBlacklistQueryAfterCreate, getBlacklistQueryAfterStatusChange } from '../lib/blacklist'
import { BlacklistCreateForm } from '../components/blacklist/BlacklistCreateForm'
import { ClearableHashInput } from '../components/blacklist/ClearableHashInput'
import { BlacklistNotice } from '../components/blacklist/BlacklistNotice'
import { BlacklistActionPanel } from '../components/blacklist/BlacklistActionPanel'
import type { BlacklistSelection } from '../components/blacklist/BlacklistActionPanel'

const severities: BlacklistSeverity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
const sources: BlacklistSource[] = ['MANUAL', 'IMPORT']

// 요청 처리와 표 렌더링을 나눠 누락된 등록자, 빈 결과, 오류도 독립적으로 확인한다.
export function BlacklistResults({ items, isLoading, error, onRetry, onSelect, disabled = false }: {
  items: BlacklistItem[]; isLoading: boolean; error: boolean; onRetry: () => void
  onSelect?: (selection: BlacklistSelection) => void; disabled?: boolean
}) {
  if (isLoading) return <p className="events-state" role="status">블랙리스트를 불러오는 중입니다.</p>
  if (error) return <div className="events-state" role="alert"><p>블랙리스트를 불러오지 못했습니다.</p>
    <button className="events-button" type="button" onClick={onRetry}>다시 시도</button></div>
  if (!items.length) return <p className="events-state">조건에 맞는 블랙리스트가 없습니다.</p>
  return <div className="events-table-scroll"><table className="events-table">
    <thead><tr>{['SHA-256', '심각도', '출처', '활성 여부', '사유', '등록자', '등록 시각', '해제 시각'].map((label) => <th scope="col" key={label}>{label}</th>)}{onSelect && <th scope="col" className="blacklist-management">관리</th>}</tr></thead>
    <tbody>{items.map((item) => <tr key={item.hashValue}>
      <td className="blacklist-hash"><code>{item.hashValue}</code></td>
      <td><span className={`events-badge events-badge--${item.severity === 'CRITICAL' || item.severity === 'HIGH' ? 'blocked' : 'pending'}`}>{item.severity}</span></td>
      <td>{item.source === 'MANUAL' ? '수동 등록' : '가져오기'}</td>
      <td>{item.isActive ? '활성' : '비활성'}</td>
      <td className="events-truncate" title={item.reason}>{item.reason || '—'}</td>
      {/* 외부 피드에는 등록자가 없을 수 있다. 이름이 없지만 ID가 있으면 ID를 보여준다. */}
      <td>{item.addedBy?.username || item.addedBy?.adminId || '—'}</td>
      <td>{new Date(item.createdAt).toLocaleString('ko-KR')}</td>
      <td>{item.deactivatedAt ? new Date(item.deactivatedAt).toLocaleString('ko-KR') : '—'}</td>
      {onSelect && <td className="blacklist-management"><div className="blacklist-actions">
        {item.isActive && <button className="events-button" type="button" disabled={disabled} onClick={() => onSelect({ item, mode: 'edit' })}>사유 수정</button>}
        <button className={`events-button ${item.isActive ? 'blacklist-danger' : 'events-button--primary'}`} type="button" disabled={disabled} onClick={() => onSelect({ item, mode: item.isActive ? 'remove' : 'activate' })}>{item.isActive ? '비활성화' : '활성화'}</button>
      </div></td>}
    </tr>)}</tbody>
  </table></div>
}

export function BlacklistPage() {
  const [hash, setHash] = useState('')
  const [severity, setSeverity] = useState<BlacklistSeverity | ''>('')
  const [source, setSource] = useState<BlacklistSource | ''>('')
  const [isActive, setIsActive] = useState(true)
  // 입력 중인 값과 실제 조회 조건을 분리해 조회 버튼을 눌렀을 때만 검색한다.
  const [query, setQuery] = useState<BlacklistQuery>({ isActive: true, page: 0, size: 20 })
  const [refreshKey, setRefreshKey] = useState(0)
  const [busy, setBusy] = useState(false)
  // state가 렌더링되기 전의 연속 클릭까지 막아 등록/수정/해제가 동시에 실행되지 않게 한다.
  const mutationLock = useRef(false)

  const [selection, setSelection] = useState<BlacklistSelection | null>(null)
  const [draftReason, setDraftReason] = useState('')
  const [createError, setCreateError] = useState('')
  const [actionError, setActionError] = useState('')
  const [notice, setNotice] = useState('')

  useEffect(() => {
    if (!notice) return
    // 성공 안내만 잠시 보여준다. 입력 오류와 서버 오류는 자동으로 숨기지 않는다.
    const timer = setTimeout(() => setNotice(''), 3000)
    return () => clearTimeout(timer)
  }, [notice])
  const requestKey = JSON.stringify(query) + ':' + refreshKey
  const [result, setResult] = useState<{ key: string; page: PageResponse<BlacklistItem> | null; error: boolean } | null>(null)
  const isLoading = result?.key !== requestKey
  const page = result?.page
  const pageNumber = page?.page ?? query.page ?? 0
  const [progress, setProgress] = useState<{ query: BlacklistQuery; refreshKey: number } | null>(null)
  const showProgress = isLoading && progress?.query === query && progress.refreshKey === refreshKey

  useEffect(() => {
    if (!isLoading) return
    // 같은 페이지 재방문도 새 요청이므로 객체 참조와 재조회 번호를 함께 비교한다.
    const timer = setTimeout(() => setProgress({ query, refreshKey }), 200)
    return () => clearTimeout(timer)
  }, [isLoading, query, refreshKey])

  useEffect(() => {
    let active = true
    getBlacklist(query)
      .then((page) => {
        if (!active) return
        // 마지막 페이지의 마지막 항목을 해제했으면 존재하는 마지막 페이지로 다시 조회한다.
        const lastPage = Math.max(0, page.totalPages - 1)
        if ((query.page ?? 0) > lastPage) { setQuery((current) => ({ ...current, page: lastPage })); return }
        setResult({ key: requestKey, page, error: false })
      })
      .catch(() => { if (active) setResult({ key: requestKey, page: null, error: true }) })
    // 필터 변경/페이지 이동 전의 늦은 응답이 최신 결과를 덮어쓰지 못하게 한다.
    return () => { active = false }
  }, [query, requestKey])

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy || isLoading) return
    setSelection(null)
    setActionError('')
    // 서버는 대소문자를 무시한 해시 앞부분 검색만 지원한다. 조건 변경 시 첫 페이지로 돌아간다.
    setQuery((current) => ({ ...current, hashValue: hash.trim() || undefined, severity: severity || undefined, source: source || undefined, isActive, page: 0 }))
    setRefreshKey((key) => key + 1)
  }

  function refresh() {
    if (busy || isLoading) return
    setSelection(null)
    setActionError('')
    setRefreshKey((key) => key + 1)
  }

  async function runChange<T>(operation: () => Promise<T>, onSuccess: (value: T) => void, onError: (message: string) => void) {
    if (mutationLock.current) return false
    mutationLock.current = true
    setBusy(true)
    setNotice('')
    onError('')
    try {
      const changed = await operation()
      onSuccess(changed)
      setSelection(null)
      setRefreshKey((key) => key + 1)
      return true
    } catch (cause) {
      onError(blacklistErrorMessage(cause))
      return false
    } finally {
      mutationLock.current = false
      setBusy(false)
    }
  }

  function submitCreate(sha256: string, reason: string) {
    return runChange(() => createBlacklist({ sha256, reason }), () => {
      // 등록 후 단건 검색하지 않고 검색 조건을 비운 활성 목록 첫 페이지로 이동한다.
      // 재등록 항목의 최초 등록 시각은 서버 정책대로 유지하므로 맨 위로 올리지는 않는다.
      setHash('')
      setSeverity('')
      setSource('')
      setIsActive(true)
      setQuery(getBlacklistQueryAfterCreate(query))
      setNotice('블랙리스트에 등록했습니다.')
    }, setCreateError)
  }

  function selectAction(next: BlacklistSelection) {
    if (busy || isLoading) return
    setSelection(next)
    setDraftReason(next.item.reason)
    setActionError('')
    setNotice('')
  }

  function confirmAction() {
    if (!selection || mutationLock.current) return
    const target = selection
    if (target.mode !== 'remove' && !draftReason.trim()) { setActionError(target.mode === 'activate' ? '활성화 사유를 입력해 주세요.' : '수정할 사유를 입력해 주세요.'); return }
    // 활성화 전용 API는 없으므로 기존 SHA와 확인한 사유로 재등록한다. 실패하면 패널/입력을 유지한다.
    const operation: () => Promise<unknown> = target.mode === 'edit'
      ? () => updateBlacklistReason(target.item.hashValue, draftReason.trim())
      : target.mode === 'activate'
        ? () => createBlacklist({ sha256: target.item.hashValue, reason: draftReason.trim() })
        : () => removeBlacklist(target.item.hashValue)
    void runChange(operation, () => {
      if (target.mode === 'edit') { setNotice('사유를 수정했습니다.'); return }
      const nextQuery = getBlacklistQueryAfterStatusChange(query, target.item.hashValue, target.mode === 'activate')
      // 입력값과 실제 조회 조건을 함께 복원해 화면에 서로 다른 조건이 남지 않도록 한다.
      setHash(nextQuery.hashValue ?? '')
      setSeverity(nextQuery.severity ?? '')
      setSource(nextQuery.source ?? '')
      setIsActive(nextQuery.isActive ?? true)
      setQuery(nextQuery)
      setNotice(target.mode === 'activate' ? '블랙리스트를 활성화했습니다.' : '블랙리스트를 비활성화했습니다.')
    }, setActionError)
  }

  function movePage(offset: number) {
    const next = pageNumber + offset
    if (busy || isLoading || !page || next < 0 || next >= page.totalPages) return
    setSelection(null)
    setQuery((current) => ({ ...current, page: next }))
  }

  return <section className="page events-page">
    <div className="page-heading"><div><p className="eyebrow">Blacklist</p><h1>블랙리스트</h1>
    </div>
      <button className="events-button" type="button" disabled={busy || isLoading} onClick={refresh}>새로고침</button></div>
    <BlacklistCreateForm busy={busy} error={createError} onCreate={submitCreate} onClearError={() => setCreateError('')} />

    <form className="events-filters" aria-label="블랙리스트 검색" onSubmit={applyFilters}>
      <label>SHA-256 앞부분<ClearableHashInput ariaLabel="검색할 SHA-256" clearLabel="검색 SHA-256 지우기" value={hash} disabled={busy} onChange={setHash} pattern="[0-9a-fA-F]{1,64}" maxLength={64} title="1~64자리의 16진수(0~9, a~f)를 입력해 주세요." placeholder="예: 9b" /></label>
      <label>심각도<select value={severity} disabled={busy} onChange={(event) => setSeverity(event.target.value as BlacklistSeverity | '')}>
        <option value="">전체</option>{severities.map((value) => <option key={value} value={value}>{value}</option>)}
      </select></label>
      <label>출처<select value={source} disabled={busy} onChange={(event) => setSource(event.target.value as BlacklistSource | '')}>
        <option value="">전체</option><option value={sources[0]}>수동 등록</option><option value={sources[1]}>가져오기</option>
      </select></label>
      {/* 생략해도 활성 항목만 나오므로 잘못된 '전체' 선택지는 제공하지 않는다. */}
      <label>활성 여부<select value={String(isActive)} disabled={busy} onChange={(event) => setIsActive(event.target.value === 'true')}>
        <option value="true">활성</option><option value="false">비활성</option>
      </select></label>
      <button className="events-button events-button--primary" type="submit" disabled={busy || isLoading}>{showProgress ? '조회 중…' : '조회'}</button>
    </form>
    {selection && <BlacklistActionPanel selection={selection} reason={draftReason} busy={busy} error={actionError} onReasonChange={setDraftReason} onConfirm={confirmAction} onCancel={() => { if (!busy) { setSelection(null); setActionError('') } }} />}
    <div className="events-panel"><div className="events-panel-heading"><h2>블랙리스트 목록</h2>
      <span className="blacklist-request-status" role="status">{showProgress && page ? '조회 중… (이전 결과)' : ''}</span>
      <label>페이지 크기<select value={query.size} disabled={busy || isLoading} onChange={(event) => { setSelection(null); setQuery((current) => ({ ...current, page: 0, size: Number(event.target.value) })) }}>
        <option value="20">20건</option><option value="50">50건</option><option value="100">100건</option>
      </select></label></div>
      <BlacklistResults items={page?.content ?? []} isLoading={isLoading && !page} error={!isLoading && Boolean(result?.error)} onRetry={refresh} onSelect={selectAction} disabled={busy || isLoading} />
      <div className="events-pagination"><span>{pageNumber + 1} / {Math.max(page?.totalPages ?? 1, 1)}페이지{page && `, 총 ${page.totalElements.toLocaleString('ko-KR')}건`}</span><div>
        <button className="events-button" type="button" disabled={busy || isLoading || !page || pageNumber === 0} onClick={() => movePage(-1)}>이전</button>
        <button className="events-button" type="button" disabled={busy || isLoading || !page || pageNumber + 1 >= page.totalPages} onClick={() => movePage(1)}>다음</button>
      </div></div>
    </div>
    <BlacklistNotice message={notice} onDismiss={() => setNotice('')} />
  </section>
}
