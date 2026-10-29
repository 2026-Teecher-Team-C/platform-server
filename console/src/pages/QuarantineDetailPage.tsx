import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../lib/api/client'
import { getQuarantineFile, restoreQuarantineFile } from '../lib/api/quarantine'
import { QuarantineRestorePanel } from '../components/quarantine/QuarantineRestorePanel'
import { quarantineRestoreErrorMessage } from '../lib/quarantineRestore'

// 기존 조회 함수의 응답을 그대로 사용해 실제 API와 mock의 전환 구조를 유지한다.
type Detail = Awaited<ReturnType<typeof getQuarantineFile>>
type DetailError = 'not-found' | 'request' | null

// 표시 부분을 분리하면 API 응답의 빈 값과 화면 상태를 네트워크 없이도 회귀 테스트할 수 있다.
export function QuarantineDetailContent({ item, isLoading, error, onRetry }: {
  item: Detail | null
  isLoading: boolean
  error: DetailError
  onRetry: () => void
}) {
  if (error === 'not-found') {
    return <div className="event-detail-card events-state" role="alert">격리 파일을 찾을 수 없습니다. 격리 ID를 확인해 주세요.</div>
  }
  if (isLoading) {
    return <div className="event-detail-card events-state" role="status">격리 파일 상세를 불러오는 중입니다.</div>
  }
  if (error || !item) {
    return <div className="event-detail-card events-state" role="alert">
      <p>격리 파일 상세를 불러오지 못했습니다.</p>
      <button className="events-button" type="button" onClick={onRetry}>다시 시도</button>
    </div>
  }
  return <div className="event-detail-card">
    <div className="event-detail-heading">
      <div><p className="eyebrow">격리 상태</p><h2>{item.originalFilename || '파일명 없음'}</h2></div>
      <span className="events-badge">{item.status}</span>
    </div>
    {/* 파일명·복원 시각이 없는 경우와 실제 0 bytes를 구분해 표시한다. */}
    <dl className="event-detail-grid">
      <div><dt>격리 ID</dt><dd className="event-long-value">{item.quarantineId}</dd></div>
      <div><dt>상태</dt><dd>{item.status}</dd></div>
      <div><dt>파일명</dt><dd className="event-long-value">{item.originalFilename || '—'}</dd></div>
      <div><dt>파일 크기</dt><dd>{item.fileSize.toLocaleString('ko-KR')} bytes</dd></div>
      <div><dt>격리 시각</dt><dd>{new Date(item.quarantinedAt).toLocaleString('ko-KR')}</dd></div>
      <div><dt>복원 시각</dt><dd>{item.restoredAt ? new Date(item.restoredAt).toLocaleString('ko-KR') : '—'}</dd></div>
      <div><dt>PC / hostname</dt><dd>{item.agent?.hostname ?? item.hostname}</dd></div>
      {/* PC 상세 화면은 아직 없으므로 ID만 보여주고, 구현된 이벤트 상세 화면만 연결한다. */}
      <div><dt>Agent ID</dt><dd className="event-long-value">{item.agentId}</dd></div>
      <div className="event-detail-wide"><dt>SHA-256</dt><dd className="event-long-value event-hash">{item.sha256}</dd></div>
      <div className="event-detail-wide"><dt>다운로드 이벤트</dt><dd className="event-long-value"><Link className="events-detail-link" to={'/events/' + encodeURIComponent(item.eventId)}>{item.eventId}</Link></dd></div>
      <div><dt>요청 호스트</dt><dd className="event-long-value">{item.downloadEvent?.requestHost || '—'}</dd></div>
      {/* URL은 실행 가능한 링크로 만들지 않는다. 격리 원본이나 S3 저장 경로도 노출하지 않는다. */}
      <div className="event-detail-wide"><dt>요청 URL</dt><dd className="event-long-value">{item.downloadEvent?.url || '—'}</dd></div>
    </dl>
  </div>
}

export function QuarantineDetailPage() {
  const { quarantineId } = useParams()
  // URL이 바뀌면 별도 인스턴스로 만들어 이전 파일의 확인 입력이나 POST 응답을 새 화면에 가져오지 않는다.
  return <QuarantineDetailScreen key={quarantineId ?? 'missing'} quarantineId={quarantineId} />
}

function QuarantineDetailScreen({ quarantineId }: { quarantineId: string | undefined }) {
  const [refreshKey, setRefreshKey] = useState(0)
  const [result, setResult] = useState<{
    id: string
    key: number
    item: Detail | null
    error: DetailError
  } | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState('')
  const [needsReload, setNeedsReload] = useState(false)
  const [notice, setNotice] = useState('')
  const mutationLock = useRef(false)
  const requestVersion = useRef(0)
  const mounted = useRef(false)
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])
  useEffect(() => {
    if (!notice) return
    const timer = setTimeout(() => setNotice(''), 3000)
    return () => clearTimeout(timer)
  }, [notice])

  // 요청 ID와 재시도 회차가 현재 URL과 일치할 때만 결과를 보여줘 이전 파일 정보가 섞이지 않게 한다.
  const isLoading = result?.id !== quarantineId || result?.key !== refreshKey
  const item = result?.id === quarantineId ? result?.item ?? null : null
  useEffect(() => {
    if (!quarantineId) return
    let active = true
    const version = ++requestVersion.current
    getQuarantineFile(quarantineId)
      .then((item) => {
        if (!active || version !== requestVersion.current) return
        setResult({ id: quarantineId, key: refreshKey, item, error: null })
        setActionError('')
        setNeedsReload(false)
        if (item.status !== 'QUARANTINED') setConfirming(false)
      })
      .catch((cause: unknown) => {
        // 존재하지 않는 ID(404)와 재시도 가능한 조회 실패를 구분한다.
        if (!active || version !== requestVersion.current) return
        const error: DetailError = cause instanceof ApiError && cause.status === 404 ? 'not-found' : 'request'
        setResult((current) => ({ id: quarantineId, key: refreshKey, item: error === 'not-found' ? null : current?.item ?? null, error }))
      })
    // 화면을 떠나거나 ID가 바뀐 뒤 도착한 이전 응답은 무시한다. 요청 자체를 취소하는 것은 아니다.
    return () => { active = false }
  }, [quarantineId, refreshKey])

  function reload() {
    if (!busy && !isLoading) setRefreshKey((key) => key + 1)
  }

  async function confirmRestore() {
    if (!item || item.status !== 'QUARANTINED' || !confirming || mutationLock.current || isLoading || needsReload) return
    // state 갱신 전의 연속 클릭도 ref로 막는다. 이전 GET도 무효화해 복원 후 상태를 덮어쓰지 못하게 한다.
    mutationLock.current = true
    requestVersion.current++
    setBusy(true)
    setActionError('')
    setNotice('')
    try {
      const restored = await restoreQuarantineFile(item.quarantineId, reason)
      if (!mounted.current) return
      // POST 성공을 먼저 반영한다. 후속 GET이 실패해도 복원 실패로 표시하지 않는다.
      setResult((current) => current?.item ? { ...current, item: { ...current.item, status: restored.status, restoredAt: restored.restoredAt }, error: null } : current)
      setConfirming(false)
      setReason('')
      setNotice('복원과 화이트리스트 반영을 완료했습니다.')
      setRefreshKey((key) => key + 1)
    } catch (cause) {
      if (!mounted.current) return
      // 응답만 끊겼을 수 있으므로 POST를 자동 재시도하지 않고 먼저 상태 확인을 요청한다.
      setActionError(quarantineRestoreErrorMessage(cause))
      setNeedsReload(true)
    } finally {
      mutationLock.current = false
      if (mounted.current) setBusy(false)
    }
  }

  return <section className="page event-detail-page">
    <div className="page-heading">
      <div><p className="eyebrow">Quarantine file</p><h1>격리 파일 상세</h1></div>
      <div className="quarantine-restore-actions"><button className="events-button" type="button" disabled={busy || isLoading} onClick={reload}>새로고침</button>
        <Link className="events-button event-back-link" to="/quarantine">목록으로</Link></div>
    </div>
    <QuarantineDetailContent
      item={item}
      isLoading={Boolean(quarantineId) && isLoading && !item}
      error={!quarantineId ? 'not-found' : item || isLoading ? null : result?.error ?? null}
      onRetry={reload}
    />
    {!isLoading && item && result?.error && <div className="events-state" role="alert"><p>최신 상세를 불러오지 못했습니다. 이전 조회 결과를 표시합니다.</p><button className="events-button" type="button" disabled={busy} onClick={reload}>다시 시도</button></div>}
    {item && <QuarantineRestorePanel item={item} open={confirming} reason={reason} disabled={busy || isLoading} busy={busy} error={actionError} needsReload={needsReload} notice={notice}
      onStart={() => { if (!busy && !isLoading) { setConfirming(true); if (!needsReload) setActionError(''); setNotice('') } }}
      onCancel={() => { if (!busy) { setConfirming(false); setReason(''); if (!needsReload) setActionError('') } }}
      onReasonChange={setReason} onConfirm={() => { void confirmRestore() }} onReload={reload} />}
  </section>
}
