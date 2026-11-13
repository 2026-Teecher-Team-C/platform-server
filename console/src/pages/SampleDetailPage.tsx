import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { ApiError } from '../lib/api/client'
import { getSample, type SampleDetail } from '../lib/api/samples'

export function SampleDetailPage() {
  const { sha256 = '' } = useParams()
  const [search] = useSearchParams()
  const [refreshKey, setRefreshKey] = useState(0)
  const [result, setResult] = useState<{ sha256: string; refreshKey: number; sample: SampleDetail | null; error: number | null } | null>(null)
  const valid = /^[0-9a-fA-F]{64}$/.test(sha256)
  const loading = valid && (result?.sha256 !== sha256 || result?.refreshKey !== refreshKey)
  const sample = loading ? null : result?.sample
  const error = valid ? (loading ? null : result?.error) : 400
  useEffect(() => {
    if (!valid) return
    let active = true
    // SHA와 요청 회차를 묶어 저장하고 정리 시 무효화한다. 늦은 응답은 다른 샘플을 덮지 않는다.
    getSample(sha256).then(sample => {
      if (active) setResult({ sha256, refreshKey, sample, error: null })
    }).catch((cause: unknown) => {
      if (active) setResult({ sha256, refreshKey, sample: null, error: cause instanceof ApiError ? cause.status : 500 })
    })
    return () => { active = false }
  }, [sha256, refreshKey, valid])
  // 외부 주소, 쿼리, dot segment, 인코딩된 경로를 허용하지 않고 원래 이벤트 경로만 받는다.
  const candidate = search.get('returnTo') ?? ''
  const returnTo = /^\/events\/[a-zA-Z0-9_-]+$/.test(candidate) ? candidate : '/events'
  const errorMessage = error === 400 ? 'SHA-256 형식이 올바르지 않습니다.'
    : error === 404 ? '현재 샘플 메타데이터를 찾을 수 없습니다. 삭제되었거나 현재 등록되지 않은 샘플일 수 있습니다. 이벤트 당시 기록은 유지됩니다.'
    : error === 401 || error === 403 ? '조회 권한이 없습니다.' : '샘플 상세를 불러오지 못했습니다.'
  return <section className="page event-detail-page">
    <div className="page-heading"><div><p className="eyebrow">Sample</p><h1>샘플 상세</h1></div>
      <Link className="events-button event-back-link" to={returnTo}>이벤트로 돌아가기</Link></div>
    {loading ? <div className="event-detail-card events-state" role="status">샘플 상세를 불러오는 중입니다.</div>
      : error ? <div className="event-detail-card events-state" role="alert"><p>{errorMessage}</p>
        {error >= 500 && <button className="events-button" type="button" onClick={() => setRefreshKey(key => key + 1)}>다시 시도</button>}
      </div> : <div className="event-detail-card">
      <div className="event-detail-heading"><p>현재 저장된 메타데이터입니다. 이벤트 당시 평가 기록과 다를 수 있습니다.</p></div>
      {sample && <dl className="event-detail-grid">
        <div className="event-detail-wide"><dt>SHA-256</dt><dd className="event-long-value event-hash">{sample.sha256}</dd></div>
        <div className="event-detail-wide"><dt>TLSH</dt><dd className="event-long-value event-hash">{sample.fingerprint?.tlsh ?? (sample.fingerprint ? '계산된 TLSH 없음' : '지문 기록 없음')}</dd></div>
        <div><dt>블랙리스트 상태</dt><dd>{sample.blacklist ? (sample.blacklist.isActive ? '활성' : '비활성') : '현재 블랙리스트 기록 없음'}</dd></div>
        <div><dt>심각도</dt><dd>{sample.blacklist?.severity ?? '—'}</dd></div>
        <div><dt>블랙리스트 출처</dt><dd>{sample.blacklist?.source ?? '—'}</dd></div>
        <div><dt>등록 시각</dt><dd>{sample.blacklist?.registeredAt ?? '—'}</dd></div>
        <div className="event-detail-wide"><dt>등록 사유</dt><dd className="event-long-value">{sample.blacklist?.reason ?? '—'}</dd></div>
        <div><dt>지문 계산 상태</dt><dd>{sample.fingerprint?.calculationStatus ?? '—'}</dd></div>
        <div><dt>지문 출처</dt><dd>{sample.fingerprint?.provenance ?? '—'}</dd></div>
        <div><dt>알고리즘 버전</dt><dd>{sample.fingerprint?.algorithmVersion ?? '—'}</dd></div>
        <div><dt>지문 기록 시각</dt><dd>{sample.fingerprint?.calculatedAt ?? '—'}</dd></div>
        <div className="event-detail-wide"><dt>지문 출처 설명</dt><dd className="event-long-value">{sample.fingerprint?.provenanceDetail ?? '—'}</dd></div>
      </dl>}
    </div>}
  </section>
}
