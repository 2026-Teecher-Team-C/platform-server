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

function formatBlockedAt(iso: string) {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toLocaleString('ko-KR', {
    year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit',
  })
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
    <main className="appeal-page">
      <article className="appeal-card" data-state={cardState(state)}>
        <AppealBody
          state={state}
          unstable={unstable}
          token={token}
          onView={(view) => setState({ kind: 'ready', view })}
          onNotFound={() => setState({ kind: 'notFound' })}
        />
      </article>
      <p className="appeal-footnote">Teecher Security</p>
    </main>
  )
}

function cardState(state: PageState) {
  if (state.kind !== 'ready') return state.kind
  return state.view.appeal?.status.toLowerCase() ?? 'draft'
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
      <header className="appeal-header">
        <h1>소명 정보를 불러오는 중입니다</h1>
        <p role="status">
          {unstable ? '서버에 연결하지 못했습니다. 잠시 후 자동으로 다시 시도합니다.' : '잠시만 기다려 주세요.'}
        </p>
      </header>
    )
  }
  if (state.kind === 'notFound') {
    return (
      <header className="appeal-header">
        <h1>소명 링크를 열 수 없습니다</h1>
        <p role="alert">링크가 만료되었거나 잘못되었습니다. 이벤트 ID를 보안 담당자에게 알려 주세요.</p>
      </header>
    )
  }

  const { view } = state
  return (
    <>
      <header className="appeal-header">
        <p className="appeal-eyebrow">다운로드 차단 소명</p>
        <h1>{view.appeal ? '소명을 접수했습니다' : '이 파일이 업무에 필요하신가요?'}</h1>
        <p>
          {view.appeal
            ? '보안 담당자의 결정이 이 화면에 바로 표시됩니다.'
            : '필요한 이유를 적어 보내면 보안 담당자가 검토합니다.'}
        </p>
      </header>
      <BlockSummary view={view} />
      {view.appeal
        ? <AppealStatusPanel key={view.appeal.status} appeal={view.appeal} unstable={unstable} />
        : <AppealForm token={token} onView={onView} onNotFound={onNotFound} />}
    </>
  )
}

function BlockSummary({ view }: { view: AppealView }) {
  return (
    <dl className="appeal-summary">
      <div>
        <dt>차단 이유</dt>
        <dd>
          {view.verdict ? (
            <>
              <strong className="appeal-threat">{view.verdict.threat}</strong>
              <span className="appeal-why">{view.verdict.summary}</span>
            </>
          ) : (
            <span className="appeal-why">판정 근거를 불러오지 못했습니다</span>
          )}
        </dd>
      </div>
      <div>
        <dt>파일</dt>
        {/* 파일명은 공격자가 정할 수 있는 값이다. 링크로 만들지 않고 텍스트로만 보여 준다. */}
        <dd className="appeal-filename">{view.filename ?? '파일 이름을 알 수 없습니다'}</dd>
      </div>
      {view.blockedAt ? (
        <div>
          <dt>차단 시각</dt>
          <dd>{formatBlockedAt(view.blockedAt)}</dd>
        </div>
      ) : null}
    </dl>
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
    <form className="appeal-form" onSubmit={handleSubmit} noValidate>
      <label className="appeal-field">
        <span>소명 사유</span>
        <textarea
          name="reason"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="예: 거래처에서 받은 업무용 설치 파일입니다."
          rows={5}
          disabled={submitting}
        />
      </label>
      <div className="appeal-form-footer">
        <span className={'appeal-count' + (length > REASON_MAX ? ' is-over' : '')} aria-live="polite">
          {length} / {REASON_MAX}
        </span>
        <button className="appeal-submit" type="submit" disabled={!valid || submitting}>
          소명 제출
        </button>
      </div>
      {error ? <p className="appeal-error" role="alert">{error}</p> : null}
    </form>
  )
}

const STATUS_COPY = {
  PENDING: { icon: '⏳', title: '보안 담당자가 검토하고 있습니다.' },
  APPROVED: { icon: '✅', title: '승인되었습니다. 원래 페이지로 돌아가 다시 받아 주세요.' },
  REJECTED: { icon: '⛔', title: '거절되었습니다.' },
} as const

function AppealStatusPanel({ appeal, unstable }: { appeal: SubmittedAppeal; unstable: boolean }) {
  const copy = STATUS_COPY[appeal.status]
  return (
    <section className={'appeal-status appeal-status--' + appeal.status.toLowerCase()}>
      <div className="appeal-status-head" role="status" aria-live="polite">
        <span className="appeal-status-icon" aria-hidden="true">{copy.icon}</span>
        <p>{copy.title}</p>
      </div>
      {appeal.status === 'REJECTED' && appeal.decisionNote ? (
        <p className="appeal-decision">사유: {appeal.decisionNote}</p>
      ) : null}
      <div className="appeal-mine">
        <span>내가 쓴 사유</span>
        <p>{appeal.reason}</p>
      </div>
      {appeal.status === 'PENDING' ? (
        <p className="appeal-live">
          <span className="appeal-live-dot" aria-hidden="true" />
          {unstable ? '연결이 잠시 불안정합니다. 자동으로 다시 확인합니다.' : '결정이 나면 이 화면이 자동으로 바뀝니다.'}
        </p>
      ) : null}
    </section>
  )
}
