import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { getBypassDomains } from '../lib/api/policies'
import type { BypassDomainItem, BypassDomainQuery } from '../lib/api/policies'
import type { PageResponse } from '../lib/api/types'

// 요청과 렌더링을 분리해 로딩·오류·빈 결과도 네트워크 없이 테스트한다.
export function BypassDomainResults({ items, isLoading, error, onRetry, hasResult = items.length > 0 }: {
  items: BypassDomainItem[]
  isLoading: boolean
  error: boolean
  onRetry: () => void
  hasResult?: boolean
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
    <thead><tr><th scope="col">도메인</th><th scope="col">카테고리</th><th scope="col">적용 여부</th><th scope="col">사유</th><th scope="col">등록 시각</th></tr></thead>
    <tbody>{items.map((item) => <tr key={item.bypassId}>
      {/* 검사 예외 패턴은 실제 접속 링크가 아니라 확인용 텍스트로 보여준다. */}
      <td className="events-truncate" title={item.host}>{item.host}</td>
      <td>{item.category}</td><td>{item.isActive ? '적용' : '미적용'}</td>
      <td className="events-truncate" title={item.reason}>{item.reason || '—'}</td>
      <td>{new Date(item.createdAt).toLocaleString('ko-KR')}</td>
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
        if (active) setResult({ key: requestKey, page, error: false })
      })
      .catch(() => {
        if (active) setResult((previous) => ({ key: requestKey, page: previous?.page ?? null, error: true }))
      })
    // 조건 변경 전의 늦은 응답이 새 검색 결과를 덮어쓰지 않도록 이전 응답은 무시한다.
    return () => { active = false }
  }, [query, requestKey])

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (isLoading) return
    // '검색어'는 host에 매핑한다. 다른 열까지 검색하는 통합 검색은 아니다.
    setQuery((current) => ({ ...current, host: search.trim() || undefined, category: category || undefined, isActive, page: 0 }))
    setRefreshKey((key) => key + 1)
  }

  function refresh() {
    setRefreshKey((key) => key + 1)
  }

  function movePage(offset: number) {
    const next = pageNumber + offset
    // 재조회 실패 후에는 화면의 이전 결과와 요청 조건이 다를 수 있어 페이지 이동 대신 재시도한다.
    if (isLoading || error || !page || next < 0 || next >= page.totalPages) return
    setQuery((current) => ({ ...current, page: next }))
  }

  return <section className="page events-page">
    <div className="page-heading"><div>
      <p className="eyebrow">Policies</p><h1>정책</h1>
      <p>바이패스 도메인의 카테고리와 적용 여부를 조회합니다.</p>
    </div><button className="events-button" type="button" disabled={isLoading} onClick={refresh}>새로고침</button></div>
    <form className="events-filters" onSubmit={applyFilters}>
      <label>검색어<input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="도메인 검색어" /></label>
      <label>카테고리<select value={category} onChange={(event) => setCategory(event.target.value)}>
        <option value="">전체</option><option value="PINNED">PINNED</option><option value="SECURITY_UPDATE">SECURITY_UPDATE</option>
      </select></label>
      {/* isActive=false는 전체가 아니라 미적용 항목만 뜻하므로 '전체' 선택지를 만들지 않는다. */}
      <label>적용 여부<select value={String(isActive)} onChange={(event) => setIsActive(event.target.value === 'true')}>
        <option value="true">적용</option><option value="false">미적용</option>
      </select></label>
      <button className="events-button events-button--primary" type="submit" disabled={isLoading}>{showProgress ? '조회 중…' : '조회'}</button>
    </form>

    <div className="events-panel">
      <div className="events-panel-heading"><h2>바이패스 도메인 목록</h2>
        <span className="policies-request-status" role="status">{showProgress && page ? '조회 중… (이전 결과)' : ''}</span>
        <label>페이지 크기<select value={query.size} disabled={isLoading} onChange={(event) => setQuery((current) => ({ ...current, page: 0, size: Number(event.target.value) }))}>
          <option value="20">20건</option><option value="50">50건</option><option value="100">100건</option>
        </select></label>
      </div>
      <BypassDomainResults items={page?.content ?? []} isLoading={isLoading} hasResult={Boolean(page)} error={error} onRetry={refresh} />
      <div className="events-pagination"><span>{pageNumber + 1} / {Math.max(page?.totalPages ?? 1, 1)}페이지{page && `, 총 ${page.totalElements.toLocaleString('ko-KR')}건`}</span><div>
        <button className="events-button" type="button" disabled={isLoading || error || !page || pageNumber === 0} onClick={() => movePage(-1)}>이전</button>
        <button className="events-button" type="button" disabled={isLoading || error || !page || pageNumber + 1 >= page.totalPages} onClick={() => movePage(1)}>다음</button>
      </div></div>
    </div>
  </section>
}
