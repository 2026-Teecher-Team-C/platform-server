import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useAuth } from '../app/useAuth'
import { ApiError } from '../lib/api/client'
import { approveAppeal, getAdminAppeal, rejectAppeal } from '../lib/api/adminAppeals'
import type { AdminAppealDetail } from '../lib/api/adminAppeals'
import {
  APPEAL_STATUS_BADGE, APPEAL_STATUS_LABEL, appealDecisionError, appealEvidence, formatAppealFullTime,
} from '../lib/appealReview'
import { canManage } from '../lib/auth/roles'
import { notifyAppealsChanged } from '../lib/appealEvents'
import { AppealDecisionPanel, type DecisionMode } from '../components/appeals/AppealDecisionPanel'

type DetailError = 'not-found' | 'request' | null

function formatTime(iso: string | null) {
  return formatAppealFullTime(iso) ?? '—'
}

function Evidence({ item }: { item: AdminAppealDetail }) {
  const evidence = appealEvidence(item.evidenceKind, item.threat)
  if (!evidence) return <dd className="appeal-review-muted">판정 근거 없음</dd>
  const similarity = item.similarity
  return <dd>
    <p className="appeal-review-evidence">{evidence.summary}</p>
    <p className="appeal-review-evidence-meta">
      <span>{evidence.threat}</span>
      <span>{evidence.label}</span>
      {similarity?.distance != null && <span>유사도 거리 {similarity.distance}</span>}
    </p>
    {similarity?.matchedSampleSha256 && (
      <p className="appeal-review-evidence-meta">
        비교 샘플 (TLSH) <code className="event-long-value event-hash">{similarity.matchedSampleSha256}</code>
      </p>
    )}
  </dd>
}

// 판정 근거 → 파일·PC → 직원 사유 → (결정 뒤) 결정 내용 순서로, 담당자가 위에서 아래로 읽고 결정하게 둔다.
export function AppealDetailContent({ item }: { item: AdminAppealDetail }) {
  const decided = item.status !== 'PENDING'
  return <div className="event-detail-card">
    <div className="event-detail-heading">
      <div>
        <p className="eyebrow">이벤트 <span title={item.eventId}>{item.eventId.slice(0, 8)}</span></p>
        <h2>소명 요청 · {APPEAL_STATUS_LABEL[item.status]}</h2>
      </div>
      <span className={APPEAL_STATUS_BADGE[item.status]}>{APPEAL_STATUS_LABEL[item.status]}</span>
    </div>
    <dl className="event-detail-grid">
      <div className="event-detail-wide"><dt>판정 근거</dt><Evidence item={item} /></div>
      {/* 파일명·사유는 직원 쪽에서 정한 값이다. 링크로 만들지 않고 텍스트로만 보여 준다. */}
      <div><dt>파일</dt><dd className="event-long-value">{item.filename || '—'}</dd></div>
      <div><dt>블랙리스트</dt><dd>{item.blacklisted ? '활성 블랙리스트에 있음' : '없음'}</dd></div>
      <div className="event-detail-wide"><dt>SHA-256</dt><dd className="event-long-value event-hash">{item.sha256}</dd></div>
      <div><dt>PC</dt><dd className="event-long-value">{item.hostname || '—'}</dd></div>
      <div><dt>차단 시각</dt><dd>{formatTime(item.blockedAt)}</dd></div>
      <div><dt>접수 시각</dt><dd>{formatTime(item.createdAt)}</dd></div>
      <div><dt>다운로드 이벤트</dt><dd className="event-long-value">
        <Link className="events-detail-link" to={'/events/' + encodeURIComponent(item.eventId)}>{item.eventId}</Link>
      </dd></div>
      <div className="event-detail-wide"><dt>직원 사유</dt><dd className="appeal-review-reason">{item.reason}</dd></div>
      {decided && <>
        <div><dt>결정</dt><dd>{APPEAL_STATUS_LABEL[item.status]}</dd></div>
        <div><dt>결정자</dt><dd>{item.decidedBy || '—'}</dd></div>
        <div><dt>결정 시각</dt><dd>{formatTime(item.decidedAt)}</dd></div>
        <div><dt>담당자 의견</dt><dd className="appeal-review-reason">{item.decisionNote || '—'}</dd></div>
      </>}
    </dl>
  </div>
}

export function AppealDetailPage() {
  const { appealId } = useParams()
  // 주소가 바뀌면 새 인스턴스로 만들어 이전 소명의 입력·응답이 섞이지 않게 한다.
  return <AppealDetailScreen key={appealId ?? 'missing'} appealId={appealId} />
}

function AppealDetailScreen({ appealId }: { appealId: string | undefined }) {
  const { user } = useAuth()
  const [refreshKey, setRefreshKey] = useState(0)
  const [result, setResult] = useState<{ key: number; item: AdminAppealDetail | null; error: DetailError } | null>(null)
  const [mode, setMode] = useState<DecisionMode>('none')
  const [note, setNote] = useState('')
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

  const isLoading = result?.key !== refreshKey
  const item = result?.item ?? null
  useEffect(() => {
    if (!appealId) return
    let active = true
    const version = ++requestVersion.current
    getAdminAppeal(appealId)
      .then((loaded) => {
        if (!active || version !== requestVersion.current) return
        setResult({ key: refreshKey, item: loaded, error: null })
        setActionError('')
        setNeedsReload(false)
        if (loaded.status !== 'PENDING') setMode('none')
      })
      .catch((cause: unknown) => {
        if (!active || version !== requestVersion.current) return
        const error: DetailError = cause instanceof ApiError && cause.status === 404 ? 'not-found' : 'request'
        setResult((current) => ({ key: refreshKey, item: error === 'not-found' ? null : current?.item ?? null, error }))
      })
    return () => { active = false }
  }, [appealId, refreshKey])

  function reload() {
    if (!busy) setRefreshKey((key) => key + 1)
  }

  function start(next: Exclude<DecisionMode, 'none'>) {
    if (busy || isLoading) return
    setMode(next)
    setNote('')
    if (!needsReload) setActionError('')
    setNotice('')
  }

  function cancel() {
    if (busy) return
    setMode('none')
    setNote('')
    if (!needsReload) setActionError('')
  }

  async function confirm() {
    if (!item || item.status !== 'PENDING' || mode === 'none' || mutationLock.current || isLoading || needsReload) return
    // state 갱신 전의 연속 클릭도 ref로 막는다. 진행 중인 GET도 무효로 해 결정 결과를 덮어쓰지 못하게 한다.
    mutationLock.current = true
    requestVersion.current++
    setBusy(true)
    setActionError('')
    try {
      const decided = mode === 'approve' ? await approveAppeal(item.appealId, note) : await rejectAppeal(item.appealId, note)
      if (!mounted.current) return
      // 승인·거절 응답이 곧 최신 상세다. 다시 조회하지 않고 그대로 보여 준다.
      setResult((current) => ({ key: current?.key ?? refreshKey, item: decided, error: null }))
      setMode('none')
      setNote('')
      notifyAppealsChanged()
      setNotice(decided.status === 'APPROVED'
        ? '승인했습니다. 이 해시를 화이트리스트에 등록했습니다.'
        : '거절했습니다. 직원 소명 페이지에 담당자 의견이 보입니다.')
    } catch (cause) {
      if (!mounted.current) return
      const error = appealDecisionError(cause, mode === 'approve' ? 'approve' : 'reject')
      setActionError(error.message)
      setNeedsReload(error.needsReload)
    } finally {
      mutationLock.current = false
      if (mounted.current) setBusy(false)
    }
  }

  const error: DetailError = !appealId ? 'not-found' : item || isLoading ? null : result?.error ?? null
  return <section className="page event-detail-page">
    <div className="page-heading">
      <div><p className="eyebrow">Appeal</p><h1>소명 요청 상세</h1></div>
      <div className="quarantine-restore-actions">
        <button className="events-button" type="button" disabled={busy || isLoading} onClick={reload}>새로고침</button>
        <Link className="events-button event-back-link" to="/appeals">목록으로</Link>
      </div>
    </div>
    {error === 'not-found' ? (
      <div className="event-detail-card events-state" role="alert">소명 요청을 찾을 수 없습니다. 목록을 확인해 주세요.</div>
    ) : !item && isLoading ? (
      <div className="event-detail-card events-state" role="status">소명 요청을 불러오는 중입니다.</div>
    ) : !item ? (
      <div className="event-detail-card events-state" role="alert">
        <p>소명 요청을 불러오지 못했습니다.</p>
        <button className="events-button" type="button" onClick={reload}>다시 시도</button>
      </div>
    ) : <>
      <AppealDetailContent item={item} />
      {!isLoading && result?.error && <div className="events-state" role="alert">
        <p>최신 상세를 불러오지 못했습니다. 이전 조회 결과를 표시합니다.</p>
        <button className="events-button" type="button" disabled={busy} onClick={reload}>다시 시도</button>
      </div>}
      {/* 승인·거절은 ADMIN만 한다. ANALYST에게는 결정 영역 자체를 그리지 않는다. */}
      {canManage(user?.role) && (item.status === 'PENDING' || notice) && <AppealDecisionPanel
        item={item} mode={mode} note={note} disabled={busy || isLoading} busy={busy} error={actionError}
        needsReload={needsReload} notice={notice}
        onStart={start} onCancel={cancel} onNoteChange={setNote} onConfirm={() => { void confirm() }} onReload={reload} />}
    </>}
  </section>
}
