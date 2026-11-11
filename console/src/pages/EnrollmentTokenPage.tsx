import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { createEnrollmentToken } from '../lib/api/agents'
import type { CreateEnrollmentTokenResponse } from '../lib/api/agents'
import { ApiError } from '../lib/api/client'

// 라우트의 공통 인증을 사용한다. 현재 서버 정책은 로그인한 관리자 사이의 역할을 나누지 않는다.
export function EnrollmentTokenPage() {
  const pending = useRef(false)
  const generation = useRef(0)
  useEffect(() => () => { generation.current += 1 }, [])
  const [expiresInMinutes, setExpiresInMinutes] = useState('')
  const [issuedToken, setIssuedToken] = useState<CreateEnrollmentTokenResponse | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')
  const [copyLabel, setCopyLabel] = useState('복사')

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    // state 반영 전 같은 렌더에서 제출해도 발급은 한 번만 보낸다.
    if (pending.current) return

    const trimmed = expiresInMinutes.trim()
    const parsed = trimmed ? Number(trimmed) : undefined

    if (parsed !== undefined && (!Number.isInteger(parsed) || parsed < 1 || parsed > 1440)) {
      setErrorMessage('유효 시간은 1 이상 1440 이하의 정수(분)로 입력해 주세요.')
      return
    }

    pending.current = true
    const current = ++generation.current
    setIsSubmitting(true)
    setErrorMessage('')
    setIssuedToken(null)
    setCopyLabel('복사')

    try {
      const response = await createEnrollmentToken(
        parsed === undefined ? {} : { expiresInMinutes: parsed },
      )
      // 화면이 사라지거나 더 새 작업으로 바뀌면 원문을 다시 표시하지 않는다.
      if (current !== generation.current) return
      setIssuedToken(response)
    } catch (error) {
      if (current !== generation.current) return
      if (error instanceof ApiError && error.status === 400) {
        setErrorMessage('유효 시간이 서버 정책의 허용 범위를 벗어났습니다.')
      } else if (error instanceof ApiError && error.status === 401) {
        setErrorMessage('로그인이 만료되었습니다. 다시 로그인해 주세요.')
      } else if (error instanceof ApiError && error.status === 403) {
        setErrorMessage('등록 토큰 발급 권한이 없습니다.')
      } else {
        setErrorMessage('등록 토큰을 발급하지 못했습니다. 잠시 후 다시 시도해 주세요.')
      }
    } finally {
      if (current === generation.current) {
        pending.current = false
        setIsSubmitting(false)
      }
    }
  }

  async function handleCopy() {
    if (!issuedToken) {
      return
    }

    const current = generation.current
    try {
      await navigator.clipboard.writeText(issuedToken.token)
      if (current === generation.current) setCopyLabel('복사됨')
    } catch {
      if (current === generation.current) setCopyLabel('복사 실패')
    }
  }

  return (
    <section className="page enrollment-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Agent Enrollment</p>
          <h1>등록 토큰 발급</h1>
        </div>
      </div>

      <div className="enrollment-grid">
        <form className="enrollment-form-card" onSubmit={handleSubmit}>
          <div>
            <h2>새 등록 토큰</h2>
            <p>
              유효 시간을 비워두면 60분을 사용합니다. 1분부터 1440분까지 설정할 수 있습니다.
            </p>
          </div>

          <label className="login-field">
            <span>유효 시간 (분)</span>
            <input
              type="number"
              inputMode="numeric"
              min="1"
              max="1440"
              step="1"
              value={expiresInMinutes}
              onChange={(event) => setExpiresInMinutes(event.target.value)}
              placeholder="기본 60분"
              disabled={isSubmitting}
            />
          </label>

          {errorMessage ? (
            <p className="login-error" role="alert">
              {errorMessage}
            </p>
          ) : null}

          <button className="login-submit" type="submit" disabled={isSubmitting}>
            {isSubmitting ? '발급 중...' : '등록 토큰 발급'}
          </button>
        </form>

        <div className="enrollment-result-card" aria-live="polite">
          {issuedToken ? (
            <>
              <div>
                <p className="eyebrow">Issued Once</p>
                <h2>토큰이 발급되었습니다.</h2>
                <p>
                  이 평문 토큰은 지금만 확인할 수 있습니다. 서버에는 해시만 저장되며 이후 다시
                  조회할 수 없습니다.
                </p>
              </div>

              <div className="token-value-box">
                <code>{issuedToken.token}</code>
                <button type="button" onClick={handleCopy}>
                  {copyLabel}
                </button>
              </div>

              <dl className="token-meta">
                <div>
                  <dt>만료 시각</dt>
                  <dd>{new Date(issuedToken.expiresAt).toLocaleString('ko-KR')}</dd>
                </div>
                <div>
                  <dt>사용 횟수</dt>
                  <dd>1회</dd>
                </div>
              </dl>

              <p className="token-warning">
                토큰 원문을 로그, 이슈, 채팅 등에 남기지 마세요.
              </p>
            </>
          ) : (
            <div className="enrollment-empty">
              <span className="enrollment-empty-mark">1×</span>
              <strong>아직 발급된 토큰이 없습니다.</strong>
              <p>왼쪽에서 유효 시간을 설정하고 토큰을 발급해 주세요.</p>
            </div>
          )}
        </div>
      </div>
    </section>
  )
}
