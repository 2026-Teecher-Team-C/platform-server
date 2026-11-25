import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useParams } from 'react-router-dom'
import { getAppeal, submitAppeal } from '../lib/api/appeals'
import type { AppealView, SubmittedAppeal } from '../lib/api/appeals'
import { ApiError } from '../lib/api/client'

const POLL_INTERVAL_MS = 3000
const REQUEST_TIMEOUT_MS = 10_000
const REASON_MAX = 1000

// 서버와 같이 앞뒤 공백을 뺀 뒤 코드 포인트로 센다(이모지 1개 = 1자).
function reasonLength(value: string) {
  return [...value.trim()].length
}

// 사내 알림처럼 "2026-11-26 01:53" 형태로 줄여 보여 준다(브라우저 시간대 기준).
function formatBlockedAt(iso: string) {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function isNotFound(error: unknown) {
  return error instanceof ApiError && error.status === 404
}

type PageState =
  | { kind: 'loading' }
  | { kind: 'notFound' }
  | { kind: 'ready'; view: AppealView }

/**
 * 차단 페이지의 [소명하기]로 들어오는 로그인 없는 화면. 토큰이 경로에 있어 index.html에 no-referrer를 둔다.
 * 검토 중에는 3초마다 다시 조회해 승인·거절로 바뀌는 순간을 보여 준다.
 */
export function AppealPage() {
  const { token = '' } = useParams()
  const [state, setState] = useState<PageState>({ kind: 'loading' })
  const [unstable, setUnstable] = useState(false)
  const hasView = useRef(false)

  const status = state.kind === 'ready' ? state.view.appeal?.status : undefined
  // 아직 아무것도 받지 못했거나 검토 중일 때만 조회를 이어 간다. 404·소명 전·승인·거절이면 멈춘다.
  const polling = state.kind === 'loading' || status === 'PENDING'

  useEffect(() => {
    hasView.current = state.kind === 'ready'
  }, [state])

  useEffect(() => {
    if (!polling) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let inFlight = false
    const unmount = new AbortController()

    // usePolling과 같이 응답이 온 뒤에 다음 요청을 예약한다. 탭이 안 보이면 예약하지 않고 쉰다.
    async function tick() {
      timer = undefined
      if (cancelled || document.visibilityState !== 'visible') return
      inFlight = true
      try {
        const view = await getAppeal(token, AbortSignal.any([unmount.signal, AbortSignal.timeout(REQUEST_TIMEOUT_MS)]))
        if (cancelled) return
        setState({ kind: 'ready', view })
        setUnstable(false)
      } catch (error) {
        if (cancelled) return
        if (isNotFound(error)) {
          setState({ kind: 'notFound' })
          return
        }
        // 429(nginx 요청 제한, HTML 본문)·네트워크 오류·시간 초과는 일시적이다. 화면은 그대로 두고 다음 차례에 다시 묻는다.
        setUnstable(true)
      } finally {
        inFlight = false
      }
      if (!cancelled) timer = setTimeout(tick, POLL_INTERVAL_MS)
    }

    function resume() {
      if (document.visibilityState === 'visible' && !inFlight && timer === undefined) void tick()
    }

    // 처음 열 때는 바로, 제출 직후처럼 이미 화면이 있으면 3초 뒤에 묻는다.
    if (hasView.current) timer = setTimeout(tick, POLL_INTERVAL_MS)
    else void tick()
    document.addEventListener('visibilitychange', resume)

    return () => {
      cancelled = true
      clearTimeout(timer)
      unmount.abort()
      document.removeEventListener('visibilitychange', resume)
    }
  }, [polling, token])

  return (
    <div className="appeal-page">
      <header className="appeal-topbar">
        <div className="appeal-frame appeal-topbar-inner">
          <span className="appeal-brand-mark" aria-hidden="true">T</span>
          <strong>Teecher</strong>
          <span className="appeal-topbar-title">보안 정책 안내</span>
        </div>
      </header>
      <main className="appeal-frame appeal-main">
        <AppealBody
          state={state}
          unstable={unstable}
          token={token}
          onView={(view) => setState({ kind: 'ready', view })}
          onNotFound={() => setState({ kind: 'notFound' })}
        />
      </main>
      <footer className="appeal-footer">
        <p className="appeal-frame">문의: 보안 담당자에게 이벤트 ID를 알려 주세요.</p>
      </footer>
    </div>
  )
}

type BodyProps = {
  state: PageState
  unstable: boolean
  token: string
  onView: (view: AppealView) => void
  onNotFound: () => void
}

function AppealBody({ state, unstable, token, onView, onNotFound }: BodyProps) {
  if (state.kind === 'loading') {
    return (
      <section className="appeal-message">
        <h1>소명 정보를 불러오는 중입니다</h1>
        <p role="status">
          {unstable ? '서버에 연결하지 못했습니다. 잠시 후 자동으로 다시 시도합니다.' : '잠시만 기다려 주세요.'}
        </p>
      </section>
    )
  }
  if (state.kind === 'notFound') {
    return (
      <section className="appeal-message">
        <h1>소명 링크를 열 수 없습니다</h1>
        <p role="alert">링크가 만료되었거나 잘못되었습니다. 이벤트 ID를 보안 담당자에게 알려 주세요.</p>
      </section>
    )
  }

  const { view } = state
  const { appeal } = view
  return (
    <>
      <div className="appeal-heading">
        <h1>다운로드 차단 소명</h1>
        <p className="appeal-event-id">
          이벤트 ID <code title={view.eventId}>{view.eventId.slice(0, 8)}</code>
        </p>
      </div>
      {appeal ? null : (
        <p className="appeal-lead">업무에 필요한 파일이면 사유를 적어 보내 주세요. 보안 담당자가 검토합니다.</p>
      )}
      <BlockDetails view={view} />
      <section className="appeal-section" aria-label="처리 상태">
        <AppealProgress appeal={appeal} />
        {/* 알림 영역은 고정해 두고 글자만 바꾼다. 다시 만들면 화면 읽기 프로그램이 바뀐 상태를 놓친다. */}
        <div className={'appeal-status appeal-status--' + (appeal?.status.toLowerCase() ?? 'draft')}
          role="status" aria-live="polite">
          {appeal ? <StatusText appeal={appeal} /> : null}
        </div>
        {appeal?.status === 'PENDING' ? (
          <p className="appeal-live">
            {unstable ? '연결이 잠시 불안정합니다. 자동으로 다시 확인합니다.' : '결정이 나면 이 화면이 자동으로 바뀝니다.'}
          </p>
        ) : null}
      </section>
      {appeal ? (
        <dl className="appeal-rows appeal-section">
          <div>
            <dt>사유</dt>
            <dd className="appeal-reason">{appeal.reason}</dd>
          </div>
        </dl>
      ) : (
        <AppealForm token={token} onView={onView} onNotFound={onNotFound} />
      )}
    </>
  )
}

function BlockDetails({ view }: { view: AppealView }) {
  return (
    <dl className="appeal-rows appeal-section">
      {view.verdict ? (
        <>
          <div>
            <dt>위협</dt>
            <dd className="appeal-threat">{view.verdict.threat}</dd>
          </div>
          <div>
            <dt>판정</dt>
            <dd>{view.verdict.summary}</dd>
          </div>
        </>
      ) : (
        <div>
          <dt>판정</dt>
          <dd className="appeal-muted">판정 근거를 불러오지 못했습니다</dd>
        </div>
      )}
      {view.filename ? (
        <div>
          <dt>파일</dt>
          {/* 파일명은 공격자가 정할 수 있는 값이다. 링크로 만들지 않고 텍스트로만 보여 준다. */}
          <dd className="appeal-filename">{view.filename}</dd>
        </div>
      ) : null}
      {view.blockedAt ? (
        <div>
          <dt>차단 시각</dt>
          <dd>{formatBlockedAt(view.blockedAt)}</dd>
        </div>
      ) : null}
    </dl>
  )
}

type StepState = 'todo' | 'done' | 'approved' | 'rejected'

function progressSteps(appeal: SubmittedAppeal | null): { label: string; state: StepState; current: boolean }[] {
  if (!appeal) {
    return [
      { label: '접수', state: 'todo', current: false },
      { label: '검토 중', state: 'todo', current: false },
      { label: '결정', state: 'todo', current: false },
    ]
  }
  if (appeal.status === 'PENDING') {
    return [
      { label: '접수', state: 'done', current: false },
      { label: '검토 중', state: 'done', current: true },
      { label: '결정', state: 'todo', current: false },
    ]
  }
  const approved = appeal.status === 'APPROVED'
  return [
    { label: '접수', state: 'done', current: false },
    { label: '검토 중', state: 'done', current: false },
    { label: approved ? '승인' : '거절', state: approved ? 'approved' : 'rejected', current: true },
  ]
}

function AppealProgress({ appeal }: { appeal: SubmittedAppeal | null }) {
  return (
    <div className="appeal-rows">
      <div>
        <span className="appeal-row-label">진행</span>
        <ol className="appeal-progress">
          {progressSteps(appeal).map((step, index) => (
            <li key={index} data-state={step.state} aria-current={step.current ? 'step' : undefined}>
              <span className="appeal-progress-dot" aria-hidden="true" />
              {step.label}
            </li>
          ))}
        </ol>
      </div>
    </div>
  )
}

const STATUS_TEXT = {
  PENDING: '보안 담당자가 검토하고 있습니다.',
  APPROVED: '승인되었습니다. 원래 페이지로 돌아가 다시 받아 주세요.',
  REJECTED: '거절되었습니다.',
} as const

function StatusText({ appeal }: { appeal: SubmittedAppeal }) {
  return (
    <>
      <p className="appeal-status-text">{STATUS_TEXT[appeal.status]}</p>
      {appeal.status === 'REJECTED' && appeal.decisionNote ? (
        <p className="appeal-decision-note">담당자 의견: {appeal.decisionNote}</p>
      ) : null}
    </>
  )
}

type FormProps = {
  token: string
  onView: (view: AppealView) => void
  onNotFound: () => void
}
function AppealForm({ token, onView, onNotFound }: FormProps) {
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const length = reasonLength(reason)
  const valid = length >= 1 && length <= REASON_MAX

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!valid || submitting) return
    setSubmitting(true)
    setError('')
    try {
      onView(await submitAppeal(token, reason.trim()))
      return
    } catch (cause) {
      if (isNotFound(cause)) {
        onNotFound()
        return
      }
      if (cause instanceof ApiError && cause.status === 409) {
        // 이미 제출한 소명이 있다(다른 탭 등). 서버에 있는 상태를 그대로 보여 준다.
        try {
          onView(await getAppeal(token))
          return
        } catch {
          setError('이미 제출한 소명이 있습니다. 페이지를 새로고침해 주세요.')
        }
      } else if (cause instanceof ApiError && cause.status === 400) {
        setError('사유를 1자 이상 1000자 이하로 입력해 주세요.')
      } else {
        setError('제출하지 못했습니다. 잠시 후 다시 시도해 주세요.')
      }
    }
    setSubmitting(false)
  }

  return (
    <form className="appeal-form appeal-rows appeal-section" onSubmit={handleSubmit} noValidate>
      <div>
        <label className="appeal-row-label" htmlFor="appeal-reason">사유</label>
        <div className="appeal-form-body">
          <textarea
            id="appeal-reason"
            name="reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="예: 거래처에서 받은 업무용 설치 파일입니다."
            rows={5}
            disabled={submitting}
          />
          <div className="appeal-form-footer">
            <span className={'appeal-count' + (length > REASON_MAX ? ' is-over' : '')} aria-live="polite">
              {length} / {REASON_MAX}
            </span>
            <button className="appeal-submit" type="submit" disabled={!valid || submitting}>
              소명 제출
            </button>
          </div>
          {error ? <p className="appeal-error" role="alert">{error}</p> : null}
        </div>
      </div>
    </form>
  )
}
