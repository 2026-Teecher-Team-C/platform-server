import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { createBypassDomain, deactivateBypassDomain, getBypassDomains } from '../lib/api/policies'
import { ApiError } from '../lib/api/client'
import { validateBypassInput } from '../lib/bypass'
import type { BypassDomainItem, BypassDomainQuery } from '../lib/api/policies'
import type { PageResponse } from '../lib/api/types'

function unknownWriteResult(cause: unknown) {
  return !(cause instanceof ApiError && [400, 401, 403, 404, 409].includes(cause.status))
}

function ClearableDomainInput({ value, onChange, disabled, label, placeholder }: {
  value: string; onChange: (value: string) => void; disabled: boolean; label: string; placeholder: string
}) {
  const input = useRef<HTMLInputElement>(null)
  return <span className="bypass-domain-field">
    <input ref={input} aria-label={label} value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} />
    {/* 해당 입력만 비우며 폼 제출이나 조회는 하지 않는다. 바로 다시 입력할 수 있게 포커스를 복귀한다. */}
    <button className="bypass-domain-clear" type="button" aria-label={`${label} 지우기`} title={`${label} 지우기`} disabled={disabled || !value} onClick={() => { onChange(''); input.current?.focus() }}>×</button>
  </span>
}

// 요청과 렌더링을 분리해 로딩·오류·빈 결과도 네트워크 없이 테스트한다.
export function BypassDomainResults({ items, isLoading, error, onRetry, hasResult = items.length > 0, onDeactivate, actionsDisabled = false }: {
  items: BypassDomainItem[]
  isLoading: boolean
  error: boolean
  onRetry: () => void
  hasResult?: boolean
  onDeactivate?: (item: BypassDomainItem) => void
  actionsDisabled?: boolean
}) {
  // 처음 조회할 때만 표 대신 로딩을 표시한다. 재조회 중에는 빈 결과를 포함한 이전 결과를 유지한다.
  if (isLoading && !hasResult) return <p className="events-state" role="status">바이패스 도메인을 불러오는 중입니다.</p>
  const notice = error ? <div className="events-state" role="alert">
    <p>바이패스 도메인을 불러오지 못했습니다.{hasResult && ' 이전 조회 결과를 표시하고 있습니다.'}</p>
    <button className="events-button" type="button" onClick={onRetry}>다시 시도</button>
  </div> : null
  if (error && !hasResult) return notice
  if (!items.length) return <>{notice}<p className="events-state">조건에 맞는 바이패스 도메인이 없습니다.</p></>
  return <>{notice}<div className="events-table-scroll"><table className="events-table">
    <thead><tr><th scope="col">도메인</th><th scope="col">카테고리</th><th scope="col">적용 여부</th><th scope="col">사유</th><th scope="col">등록 시각</th>{onDeactivate && <th scope="col">관리</th>}</tr></thead>
    <tbody>{items.map((item) => <tr key={item.bypassId}>
      {/* 검사 예외 패턴은 실제 접속 링크가 아니라 확인용 텍스트로 보여준다. */}
      <td className="events-truncate" title={item.host}>{item.host}</td>
      <td>{item.category}</td><td>{item.isActive ? '적용' : '미적용'}</td>
      <td className="events-truncate" title={item.reason}>{item.reason || '—'}</td>
      <td>{new Date(item.createdAt).toLocaleString('ko-KR')}</td>
      {onDeactivate && <td>{item.isActive ? <button className="events-button" type="button" disabled={actionsDisabled} aria-label={`${item.host} 비활성화`} onClick={() => onDeactivate(item)}>비활성화</button> : '—'}</td>}
    </tr>)}</tbody>
  </table></div></>
}

export function PoliciesPage() {
  const [search, setSearch] = useState('')
  const [category, setCategory] = useState('')
  const [isActive, setIsActive] = useState(true)
  // 입력 중인 필터와 확정된 조회 조건을 분리한다. 기본 조회는 서버 기본값과 같은 '적용'이다.
  const [query, setQuery] = useState<BypassDomainQuery>({ isActive: true, page: 0, size: 20 })
  const [refreshKey, setRefreshKey] = useState(0)
  const [needsCheck, setNeedsCheck] = useState(false)
  const requestKey = JSON.stringify(query) + ':' + refreshKey
  const [result, setResult] = useState<{
    key: string
    page: PageResponse<BypassDomainItem> | null
    error: boolean
  } | null>(null)
  const isLoading = result?.key !== requestKey
  // 새 요청과 마지막 성공 결과를 분리해 조회 중에도 표와 페이지 정보가 사라지지 않게 한다.
  const page = result?.page
  const pageNumber = page?.page ?? 0
  const error = !isLoading && Boolean(result?.error)
  const [progressRequest, setProgressRequest] = useState<{ query: BypassDomainQuery; refreshKey: number } | null>(null)
  const showProgress = isLoading && progressRequest?.query === query && progressRequest.refreshKey === refreshKey

  useEffect(() => {
    if (!isLoading) return
    // 빠른 요청에는 문구를 깜빡이지 않고, 200ms 이상 걸리는 현재 요청에만 진행 상태를 보여준다.
    // 같은 페이지로 돌아와도 별도 요청이다. 조건 객체의 참조까지 비교해 이전 타이머 상태를 재사용하지 않는다.
    const timer = setTimeout(() => setProgressRequest({ query, refreshKey }), 200)
    return () => clearTimeout(timer)
  }, [isLoading, query, refreshKey])

  useEffect(() => {
    let active = true
    getBypassDomains(query)
      .then((page) => {
        if (!active) return
        // 해제나 다른 관리자의 변경으로 마지막 페이지가 사라졌다면 유효한 마지막 페이지를 재조회한다.
        // 범위를 벗어난 빈 응답은 표시하지 않아 이전 결과와 페이지 정보가 함께 유지된다.
        const lastPage = Math.max(0, page.totalPages - 1)
        if ((query.page ?? 0) > lastPage) {
          setQuery((current) => ({ ...current, page: lastPage }))
          return
        }
        setResult({ key: requestKey, page, error: false })
        setNeedsCheck(false)
      })
      .catch(() => {
        if (active) setResult((previous) => ({ key: requestKey, page: previous?.page ?? null, error: true }))
      })
    // 조건 변경 전의 늦은 응답이 새 검색 결과를 덮어쓰지 않도록 이전 응답은 무시한다.
    return () => { active = false }
  }, [query, requestKey])

  const [createDraft, setCreateDraft] = useState({ host: '', category: 'PINNED', reason: '' })
  const [createError, setCreateError] = useState('')
  const [notice, setNotice] = useState('')
  useEffect(() => {
    if (!notice) return
    const timer = window.setTimeout(() => setNotice(''), 3000)
    return () => window.clearTimeout(timer)
  }, [notice])
  const [busy, setBusy] = useState(false)
  // state 반영 전 연속 submit도 막기 위해 동기 ref 잠금을 함께 사용한다.
  const writeLock = useRef(false)
  const lifetime = useRef(0)
  useEffect(() => {
    lifetime.current += 1
    return () => { lifetime.current += 1 }
  }, [])

  async function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (writeLock.current || isLoading || needsCheck) return
    let input
    try { input = validateBypassInput(createDraft) } catch (error) {
      setCreateError(error instanceof Error ? error.message : '입력을 확인해 주세요.')
      return
    }
    writeLock.current = true
    const generation = lifetime.current
    setBusy(true)
    setCreateError('')
    setNotice('')
    try {
      await createBypassDomain(input)
      if (generation !== lifetime.current) return
      // 새 도메인이 이전 필터에 가려지지 않도록 적용 첫 페이지로 돌아간다. 페이지 크기만 유지한다.
      setCreateDraft({ host: '', category: 'PINNED', reason: '' })
      setSearch('')
      setCategory('')
      setIsActive(true)
      setQuery((current) => ({ isActive: true, page: 0, size: current.size }))
      setNotice('바이패스 도메인을 등록했습니다. 적용 목록의 첫 페이지로 이동합니다.')
      setRefreshKey((key) => key + 1)
    } catch (error) {
      if (generation !== lifetime.current) return
      // 실패한 입력은 남겨 수정, 재시도할 수 있게 한다. 공통 client가 인증 오류 통지도 담당한다.
      setCreateError(error instanceof ApiError && error.status === 409
        ? '이미 적용 중인 도메인입니다. 등록 도메인을 확인해 주세요.'
        : error instanceof ApiError && error.status === 400 ? '입력한 도메인, 카테고리와 사유를 확인해 주세요.'
          : '처리 결과를 확인하지 못했습니다. 입력을 유지했습니다. 목록을 새로고침해 주세요.')
      if (unknownWriteResult(error)) setNeedsCheck(true)
    } finally {
      if (generation === lifetime.current) { writeLock.current = false; setBusy(false) }
    }
  }

  const [target, setTarget] = useState<BypassDomainItem | null>(null)
  const [confirmation, setConfirmation] = useState('')
  const [deactivateError, setDeactivateError] = useState('')
  const confirmationInput = useRef<HTMLInputElement>(null)
  useEffect(() => { if (target) confirmationInput.current?.focus() }, [target])

  function selectTarget(item: BypassDomainItem) {
    if (writeLock.current || isLoading || error || needsCheck || !item.isActive) return
    setTarget(item)
    setConfirmation('')
    setDeactivateError('')
  }

  async function deactivate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (writeLock.current || isLoading || error || needsCheck || !target || confirmation !== target.host) return
    writeLock.current = true
    const generation = lifetime.current
    setBusy(true)
    setDeactivateError('')
    setNotice('')
    try {
      await deactivateBypassDomain(target.bypassId)
      if (generation !== lifetime.current) return
      setTarget(null)
      setConfirmation('')
      setNotice(`${target.host} 도메인을 비활성화했습니다. 미적용 목록에서 확인할 수 있습니다.`)
      setRefreshKey((key) => key + 1)
    } catch (error) {
      if (generation !== lifetime.current) return
      setDeactivateError(error instanceof ApiError && error.status === 404
        ? '이미 미적용 상태이거나 존재하지 않는 도메인입니다. 목록을 새로고침해 확인해 주세요.'
        : '처리 결과를 확인하지 못했습니다. 확인 입력을 유지했습니다. 목록을 새로고침해 주세요.')
      if (unknownWriteResult(error)) setNeedsCheck(true)
    } finally {
      // 페이지를 떠난 뒤 끝난 요청은 상태 변경, 재조회, 잠금 해제를 실행하지 않는다.
      if (generation === lifetime.current) { writeLock.current = false; setBusy(false) }
    }
  }

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (writeLock.current || isLoading || needsCheck) return
    // '검색어'는 host에 매핑한다. 다른 열까지 검색하는 통합 검색은 아니다.
    setQuery((current) => ({ ...current, host: search.trim() || undefined, category: category || undefined, isActive, page: 0 }))
    setRefreshKey((key) => key + 1)
  }

  function refresh() {
    if (writeLock.current || isLoading) return
    // 쓰기 결과를 확인하기 전 재전송하지 않는다. 새 GET이 실패하면 잠금은 유지한다.
    if (needsCheck) { setCreateError(''); setDeactivateError(''); setTarget(null); setConfirmation('') }
    setRefreshKey((key) => key + 1)
  }

  function movePage(offset: number) {
    const next = pageNumber + offset
    // 재조회 실패 후에는 화면의 이전 결과와 요청 조건이 다를 수 있어 페이지 이동 대신 재시도한다.
    if (writeLock.current || isLoading || error || needsCheck || !page || next < 0 || next >= page.totalPages) return
    setQuery((current) => ({ ...current, page: next }))
  }

  return <section className="page events-page">
    <div className="page-heading"><div>
      <p className="eyebrow">Policies</p><h1>정책</h1>
    </div><button className="events-button" type="button" disabled={busy || isLoading} onClick={refresh}>새로고침</button></div>
    <div className="events-panel bypass-write-panel">
      <h2>바이패스 도메인 등록</h2>
      <p>등록한 도메인은 검사에서 제외됩니다. 미적용 도메인을 다시 등록하면 분류와 사유가 바뀌고 최초 등록 시각은 유지됩니다.</p>
      <form className="events-filters" aria-label="바이패스 도메인 등록" onSubmit={register} noValidate>
        <label>도메인<ClearableDomainInput label="등록 도메인" value={createDraft.host} disabled={busy} onChange={(host) => setCreateDraft((draft) => ({ ...draft, host }))} placeholder="update.example.com" /></label>
        <label>카테고리<select aria-label="등록 카테고리" value={createDraft.category} disabled={busy} onChange={(event) => setCreateDraft((draft) => ({ ...draft, category: event.target.value }))}>
          <option value="PINNED">PINNED</option><option value="SECURITY_UPDATE">SECURITY_UPDATE</option>
        </select></label>
        <label>사유<input aria-label="등록 사유" value={createDraft.reason} disabled={busy} onChange={(event) => setCreateDraft((draft) => ({ ...draft, reason: event.target.value }))} /></label>
        <button className="events-button events-button--primary" type="submit" disabled={busy || isLoading || needsCheck}>{busy ? '처리 중…' : '등록'}</button>
      </form>
      {createError && <p role="alert">{createError}</p>}
    </div>
    {target && <div className="events-panel bypass-write-panel" role="region" aria-label="바이패스 비활성화 확인">
      <h2>바이패스 비활성화</h2>
      <p><strong>{target.host}</strong>, {target.category}</p>
      <p>비활성화하면 이 도메인은 다시 검사 대상이 됩니다. 이력은 삭제하지 않습니다.</p>
      <form className="events-filters" onSubmit={deactivate}>
        <label>확인할 도메인을 그대로 입력하세요<input ref={confirmationInput} aria-label="비활성화 확인 도메인" value={confirmation} disabled={busy} onChange={(event) => setConfirmation(event.target.value)} /></label>
        <button className="events-button" type="submit" disabled={busy || isLoading || error || needsCheck || confirmation !== target.host}>비활성화 확인</button>
        <button className="events-button" type="button" disabled={busy} onClick={() => { setTarget(null); setConfirmation(''); setDeactivateError('') }}>취소</button>
      </form>
      {deactivateError && <p role="alert">{deactivateError}</p>}
    </div>}
    {notice && <div className="bypass-notice" role="status"><span>{notice}</span><button type="button" className="bypass-notice-close" aria-label="알림 닫기" onClick={() => setNotice('')}>×</button></div>}
    {needsCheck && <div className="events-state" role="alert"><p>서버에서 처리됐을 수 있습니다. 목록을 확인한 뒤 다시 변경해 주세요.</p><button className="events-button" type="button" disabled={busy || isLoading} onClick={refresh}>처리 상태 새로고침</button></div>}
    <form className="events-filters" onSubmit={applyFilters}>
      <label>검색어<ClearableDomainInput label="도메인 검색어" value={search} disabled={busy} onChange={setSearch} placeholder="도메인 검색어" /></label>
      <label>카테고리<select value={category} onChange={(event) => setCategory(event.target.value)}>
        <option value="">전체</option><option value="PINNED">PINNED</option><option value="SECURITY_UPDATE">SECURITY_UPDATE</option>
      </select></label>
      {/* isActive=false는 전체가 아니라 미적용 항목만 뜻하므로 '전체' 선택지를 만들지 않는다. */}
      <label>적용 여부<select value={String(isActive)} onChange={(event) => setIsActive(event.target.value === 'true')}>
        <option value="true">적용</option><option value="false">미적용</option>
      </select></label>
      <button className="events-button events-button--primary" type="submit" disabled={busy || isLoading || needsCheck}>{showProgress ? '조회 중…' : '조회'}</button>
    </form>

    <div className="events-panel">
      <div className="events-panel-heading"><h2>바이패스 도메인 목록</h2>
        <span className="policies-request-status" role="status">{showProgress && page ? '조회 중… (이전 결과)' : ''}</span>
        <label>페이지 크기<select value={query.size} disabled={busy || isLoading || needsCheck} onChange={(event) => setQuery((current) => ({ ...current, page: 0, size: Number(event.target.value) }))}>
          <option value="20">20건</option><option value="50">50건</option><option value="100">100건</option>
        </select></label>
      </div>
      <BypassDomainResults items={page?.content ?? []} isLoading={isLoading} hasResult={Boolean(page)} error={error} onRetry={refresh} onDeactivate={selectTarget} actionsDisabled={busy || isLoading || error || needsCheck} />
      <div className="events-pagination"><span>{pageNumber + 1} / {Math.max(page?.totalPages ?? 1, 1)}페이지{page && `, 총 ${page.totalElements.toLocaleString('ko-KR')}건`}</span><div>
        <button className="events-button" type="button" disabled={busy || isLoading || error || needsCheck || !page || pageNumber === 0} onClick={() => movePage(-1)}>이전</button>
        <button className="events-button" type="button" disabled={busy || isLoading || error || needsCheck || !page || pageNumber + 1 >= page.totalPages} onClick={() => movePage(1)}>다음</button>
      </div></div>
    </div>
  </section>
}
