import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../lib/api/client'
import { getQuarantineFile } from '../lib/api/quarantine'

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
  const [refreshKey, setRefreshKey] = useState(0)
  const [result, setResult] = useState<{
    id: string
    key: number
    item: Detail | null
    error: DetailError
  } | null>(null)

  // 요청 ID와 재시도 회차가 현재 URL과 일치할 때만 결과를 보여줘 이전 파일 정보가 섞이지 않게 한다.
  const isLoading = result?.id !== quarantineId || result?.key !== refreshKey
  useEffect(() => {
    if (!quarantineId) return
    let active = true
    getQuarantineFile(quarantineId)
      .then((item) => {
        if (active) setResult({ id: quarantineId, key: refreshKey, item, error: null })
      })
      .catch((cause: unknown) => {
        // 존재하지 않는 ID(404)와 재시도 가능한 조회 실패를 구분한다.
        if (active) setResult({ id: quarantineId, key: refreshKey, item: null, error: cause instanceof ApiError && cause.status === 404 ? 'not-found' : 'request' })
      })
    // 화면을 떠나거나 ID가 바뀐 뒤 도착한 이전 응답은 무시한다. 요청 자체를 취소하는 것은 아니다.
    return () => { active = false }
  }, [quarantineId, refreshKey])

  return <section className="page event-detail-page">
    <div className="page-heading">
      <div><p className="eyebrow">Quarantine file</p><h1>격리 파일 상세</h1><p>격리 파일의 정보와 연관 다운로드 이벤트를 확인합니다.</p></div>
      <Link className="events-button event-back-link" to="/quarantine">목록으로</Link>
    </div>
    <QuarantineDetailContent
      item={isLoading ? null : result?.item ?? null}
      isLoading={Boolean(quarantineId) && isLoading}
      error={!quarantineId ? 'not-found' : isLoading ? null : result?.error ?? null}
      onRetry={() => setRefreshKey((key) => key + 1)}
    />
  </section>
}
