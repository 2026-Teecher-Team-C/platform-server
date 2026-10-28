import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { getAgents } from '../lib/api/agents'
import type { AgentListResponse, AgentQuery, AgentStatus, OsPlatform } from '../lib/api/agents'

export function AgentsPage() {
  const [hostname, setHostname] = useState('')
  const [status, setStatus] = useState<AgentStatus | ''>('')
  const [osPlatform, setOsPlatform] = useState<OsPlatform | ''>('')
  const [query, setQuery] = useState<AgentQuery>({ page: 0, size: 20 })
  const [refreshKey, setRefreshKey] = useState(0)
  const requestKey = JSON.stringify(query) + ':' + refreshKey
  // 요청 조건과 마지막 응답을 분리해 재조회 중에도 표를 유지한다. 페이지 번호와 총 개수는 표시 중인 응답 기준이다.
  const [result, setResult] = useState<{ key: string; page: AgentListResponse | null; error: boolean } | null>(null)
  const page = result?.page
  const pageNumber = page?.page ?? query.page ?? 0
  const isLoading = result?.key !== requestKey
  const error = Boolean(result?.error) && !isLoading
  // 같은 요청은 중복 실행하지 않되, 사용자가 다른 검색 조건을 적용하면 이전 요청을 대체할 수 있다.
  const hasSearchChanges = (query.hostname ?? '') !== hostname.trim() || (query.status ?? '') !== status || (query.osPlatform ?? '') !== osPlatform || (query.page ?? 0) !== 0
  const [progress, setProgress] = useState<{ query: AgentQuery; refreshKey: number } | null>(null)
  const showProgress = isLoading && progress?.query === query && progress.refreshKey === refreshKey

  useEffect(() => {
    if (!isLoading) return
    // 빠른 재조회는 표를 그대로 유지하고, 느린 요청만 표시한다. 같은 조건의 재조회도 별도 요청으로 구분한다.
    const timer = setTimeout(() => setProgress({ query, refreshKey }), 200)
    return () => clearTimeout(timer)
  }, [isLoading, query, refreshKey])

  // 도메인 API가 공통 클라이언트를 통해 mock 또는 실제 서버를 조회한다.
  useEffect(() => {
    // 조건 변경, 언마운트 시 이전 요청을 무효화한다. 늦은 성공과 실패 모두 최신 상태를 덮어쓰지 못한다.
    let active = true
    getAgents(query).then((nextPage) => {
      if (!active) return
      // 다른 관리자가 PC를 변경해 마지막 페이지가 없어지면 존재하는 페이지로 다시 조회한다.
      const lastPage = Math.max(0, nextPage.totalPages - 1)
      if ((query.page ?? 0) > lastPage) { setQuery((current) => ({ ...current, page: lastPage })); return }
      setResult({ key: requestKey, page: nextPage, error: false })
    }).catch(() => {
      // 오류를 빈 검색 결과로 숨기지 않는다. 재시도는 현재 조회 조건을 그대로 사용한다.
      if (active) {
        setResult((current) => ({ key: requestKey, page: current?.page ?? null, error: true }))
      }
    })
    return () => { active = false }
  }, [query, requestKey])

  function refresh() {
    if (isLoading) return
    setRefreshKey((key) => key + 1)
  }

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (isLoading && !hasSearchChanges) return
    // 입력값과 실제 조회 조건을 분리한다. 조회를 누를 때만 필터를 적용하고 첫 페이지로 돌아간다.
    setQuery((current) => ({ hostname: hostname.trim() || undefined, status: status || undefined, osPlatform: osPlatform || undefined, page: 0, size: current.size }))
    setRefreshKey((key) => key + 1)
  }

  function movePage(number: number) {
    if (isLoading || error || !page || number < 0 || number >= page.totalPages) return
    setQuery((current) => ({ ...current, page: number }))
  }

  return (
    <section className="page events-page">
      <div className="page-heading"><div>
        <p className="eyebrow">PC agents</p><h1>PC 현황</h1>
        <p>등록된 에이전트의 상태, 버전, 마지막 하트비트를 확인합니다.</p>
      </div><button className="events-button" type="button" onClick={refresh} disabled={isLoading}>새로고침</button></div>
      <form className="events-filters" onSubmit={applyFilters}>
        <label>호스트 이름<input value={hostname} onChange={(event) => setHostname(event.target.value)} placeholder="호스트 이름 일부" /></label>
        <label>상태<select value={status} onChange={(event) => setStatus(event.target.value as AgentStatus | '')}>
          <option value="">전체</option><option value="ACTIVE">ACTIVE</option><option value="INACTIVE">INACTIVE</option><option value="REVOKED">REVOKED</option>
        </select></label>
        <label>운영체제<select value={osPlatform} onChange={(event) => setOsPlatform(event.target.value as OsPlatform | '')}>
          <option value="">전체</option><option value="WINDOWS">WINDOWS</option><option value="MACOS">MACOS</option><option value="LINUX">LINUX</option>
        </select></label>
        <button className="events-button events-button--primary" type="submit" disabled={isLoading && !hasSearchChanges}>조회</button>
      </form>
      <div className="events-panel">
        <div className="events-panel-heading"><h2>PC 목록</h2>
          <span className="agents-request-status">{showProgress && page && <span role="status">조회 중… (이전 결과)</span>}</span>
          <label>페이지 크기<select value={query.size} disabled={isLoading} onChange={(event) => {
            // 크기가 바뀌면 기존 페이지의 범위도 달라지므로 첫 페이지부터 다시 조회한다.
            setQuery((current) => ({ ...current, size: Number(event.target.value), page: 0 }))
          }}>
            <option value="20">20건</option><option value="50">50건</option><option value="100">100건</option>
          </select></label>
        </div>
        {error && <div className="events-state" role="alert">
          <p>PC 목록을 불러오지 못했습니다.{page && ' 아래에는 이전 조회 결과를 표시합니다.'}</p>
          <button className="events-button" type="button" onClick={refresh}>다시 시도</button>
        </div>}
        {!page ? (!error && <p className="events-state" role="status">PC 목록을 불러오는 중입니다.</p>) : !page.content.length ? (
          <p className="events-state">조건에 맞는 PC가 없습니다.</p>
        ) : (
          <div className="events-table-scroll"><table className="events-table">
            <thead><tr>
              <th scope="col">PC / hostname</th><th scope="col">Agent ID</th><th scope="col">운영체제</th>
              <th scope="col">버전</th><th scope="col">상태</th><th scope="col">마지막 하트비트</th>
            </tr></thead>
            <tbody>{page.content.map((item) => <tr key={item.agentId}>
              <td>{item.hostname}</td><td>{item.agentId}</td><td>{item.osPlatform}</td>
              <td>{item.agentVersion}</td><td>{item.status}</td>
              {/* null은 장애나 1970년 시각이 아니라 아직 하트비트를 받지 않았다는 뜻이다. */}
              <td>{item.lastHeartbeatAt === null ? '미수신' : new Date(item.lastHeartbeatAt).toLocaleString('ko-KR')}</td>
            </tr>)}</tbody>
          </table></div>
        )}
        <div className="events-pagination"><span>{pageNumber + 1} / {Math.max(page?.totalPages ?? 1, 1)}페이지{page && `, 총 ${page.totalElements.toLocaleString('ko-KR')}대`}</span><div>
          <button className="events-button" type="button" disabled={isLoading || error || !page || pageNumber === 0} onClick={() => movePage(pageNumber - 1)}>이전</button>
          <button className="events-button" type="button" disabled={isLoading || error || !page || pageNumber + 1 >= page.totalPages} onClick={() => movePage(pageNumber + 1)}>다음</button>
        </div></div>
      </div>
    </section>
  )
}
