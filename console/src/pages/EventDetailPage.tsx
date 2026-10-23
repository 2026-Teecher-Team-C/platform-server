import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../lib/api/client'
import { getDownloadEvent } from '../lib/api/events'
import type { DownloadEventDetail } from '../lib/api/events'

function displayDate(value: string | null | undefined) {
  // FAIL_CLOSE처럼 일부 시각이 없는 이벤트도 화면이 깨지지 않도록 대체 문구를 쓴다.
  return value ? new Date(value).toLocaleString('ko-KR') : '—'
}

function displayBytes(value: number | null) {
  return value === null ? '—' : `${value.toLocaleString('ko-KR')} bytes`
}

export function EventDetailPage() {
  const { eventId } = useParams()
  // 요청 대상과 재시도 회차를 함께 저장해 다른 이벤트의 오래된 응답이 보이지 않게 한다.
  const [result, setResult] = useState<{
    eventId: string
    refreshKey: number
    event: DownloadEventDetail | null
    error: 'not-found' | 'request' | null
  } | null>(null)
  const [refreshKey, setRefreshKey] = useState(0)
  const isLoading = result?.eventId !== eventId || result?.refreshKey !== refreshKey
  const event = isLoading ? null : result?.event
  const error = isLoading ? null : result?.error

  // URL의 eventId로 기존 API client를 호출한다. 현재 서버에는 상세 API가 없어 mock 모드에서 동작한다.
  useEffect(() => {
    if (!eventId) return
    let active = true
    getDownloadEvent(eventId)
      .then((result) => {
        // 화면을 떠난 뒤 완료된 요청은 현재 상세 화면 상태에 반영하지 않는다.
        if (active) {
          setResult({ eventId, refreshKey, event: result, error: null })
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          // 존재하지 않는 이벤트(404)와 일시적인 통신 오류는 다른 안내를 보여준다.
          setResult({ eventId, refreshKey, event: null, error: cause instanceof ApiError && cause.status === 404 ? 'not-found' : 'request' })
        }
      })
    return () => { active = false }
  }, [eventId, refreshKey])

  function retry() {
    setRefreshKey((key) => key + 1)
  }

  return (
    <section className="page event-detail-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Download event</p>
          <h1>이벤트 상세</h1>
          <p>다운로드 판정과 PC 정보를 확인합니다.</p>
        </div>
        <Link className="events-button event-back-link" to="/events">목록으로</Link>
      </div>

      {!eventId || error === 'not-found' ? (
        <div className="event-detail-card events-state" role="alert">
          <p>이벤트를 찾을 수 없습니다. 이벤트 ID를 확인해 주세요.</p>
          <Link className="events-detail-link" to="/events">목록으로 돌아가기</Link>
        </div>
      ) : isLoading ? (
        <div className="event-detail-card events-state" role="status">이벤트 상세를 불러오는 중입니다.</div>
      ) : error ? (
        <div className="event-detail-card events-state" role="alert">
          <p>이벤트 상세를 불러오지 못했습니다.</p>
          <button className="events-button" type="button" onClick={retry}>다시 시도</button>
        </div>
      ) : event ? (
        <div className="event-detail-card">
          <div className="event-detail-heading">
            <div>
              <p className="eyebrow">판정 결과</p>
              <h2>{event.filename || '파일명 없음'}</h2>
            </div>
            <span className={'events-badge events-badge--' + event.decision.toLowerCase()}>{event.decision}</span>
          </div>
          {/* 현재 상세 타입에서 비어 있을 수 있는 필드는 '—'로 표시하고 임의의 분석 정보를 만들지 않는다. */}
          <dl className="event-detail-grid">
            <div><dt>이벤트 ID</dt><dd className="event-long-value">{event.eventId}</dd></div>
            <div><dt>발생 시각</dt><dd>{displayDate(event.createdAt)}</dd></div>
            <div><dt>Agent ID</dt><dd className="event-long-value">{event.agentId}</dd></div>
            <div><dt>PC / hostname</dt><dd>{event.hostname}</dd></div>
            <div><dt>판정 소스</dt><dd>{event.decisionSource ?? '—'}</dd></div>
            <div><dt>Cache Hit</dt><dd>{event.cacheHit ? 'Hit' : 'Miss'}</dd></div>
            <div><dt>파일명</dt><dd className="event-long-value">{event.filename || '—'}</dd></div>
            <div><dt>MIME type</dt><dd className="event-long-value">{event.mimeType || '—'}</dd></div>
            <div><dt>파일 크기</dt><dd>{displayBytes(event.fileSize)}</dd></div>
            <div><dt>보류 시간</dt><dd>{event.holdDurationMs === null ? '—' : `${event.holdDurationMs.toLocaleString()} ms`}</dd></div>
            <div><dt>전송량</dt><dd>{displayBytes(event.bytesUploaded)}</dd></div>
            <div><dt>보류 시작</dt><dd>{displayDate(event.heldAt)}</dd></div>
            <div><dt>판정 시각</dt><dd>{displayDate(event.decidedAt)}</dd></div>
            <div><dt>요청 호스트</dt><dd className="event-long-value">{event.requestHost || '—'}</dd></div>
            <div className="event-detail-wide"><dt>SHA-256</dt><dd className="event-long-value event-hash">{event.sha256 || '—'}</dd></div>
            <div className="event-detail-wide"><dt>URL</dt><dd className="event-long-value">{event.url || '—'}</dd></div>
          </dl>
        </div>
      ) : null}
    </section>
  )
}
