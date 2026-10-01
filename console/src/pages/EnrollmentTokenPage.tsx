import { useState } from 'react'
import type { FormEvent } from 'react'
import { useAuth } from '../app/useAuth'
import { createEnrollmentToken } from '../lib/api/agents'
import type { CreateEnrollmentTokenResponse } from '../lib/api/agents'
import { ApiError } from '../lib/api/client'

export function EnrollmentTokenPage() {
  const { user } = useAuth()
  const [expiresInMinutes, setExpiresInMinutes] = useState('')
  const [issuedToken, setIssuedToken] = useState<CreateEnrollmentTokenResponse | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')
  const [copyLabel, setCopyLabel] = useState('복사')

  if (user?.role !== 'ADMIN') {
    return (
      <section className="page">
        <div className="page-heading">
          <div>
            <p className="eyebrow">Agent Enrollment</p>
            <h1>등록 토큰 발급</h1>
            <p>등록 토큰 발급은 ADMIN 권한이 필요합니다.</p>
          </div>
        </div>

        <div className="access-denied-panel">
          <strong>접근 권한이 없습니다.</strong>
          <p>현재 계정 권한: {user?.role ?? '-'}</p>
        </div>
      </section>
    )
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const trimmed = expiresInMinutes.trim()
    const parsed = trimmed ? Number(trimmed) : undefined

    if (parsed !== undefined && (!Number.isInteger(parsed) || parsed <= 0)) {
      setErrorMessage('유효 시간은 1 이상의 정수(분)로 입력해 주세요.')
      return
    }

    setIsSubmitting(true)
    setErrorMessage('')
    setIssuedToken(null)
    setCopyLabel('복사')

    try {
      const response = await createEnrollmentToken(
        parsed === undefined ? {} : { expiresInMinutes: parsed },
      )
      setIssuedToken(response)
    } catch (error) {
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
      setIsSubmitting(false)
    }
  }

  async function handleCopy() {
    if (!issuedToken) {
      return
    }

    try {
      await navigator.clipboard.writeText(issuedToken.token)
      setCopyLabel('복사됨')
    } catch {
      setCopyLabel('복사 실패')
    }
  }

  return (
    <section className="page enrollment-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Agent Enrollment</p>
          <h1>등록 토큰 발급</h1>
          <p>새 PC에 Agent를 등록할 때 사용할 1회용 토큰을 발급합니다.</p>
        </div>
        <span className="phase-badge">ADMIN</span>
      </div>

      <div className="enrollment-grid">
        <form className="enrollment-form-card" onSubmit={handleSubmit}>
          <div>
            <h2>새 등록 토큰</h2>
            <p>
              유효 시간을 비워두면 서버 기본 정책을 사용합니다. 최대 허용 시간은 서버 정책을
              따릅니다.
            </p>
          </div>

          <label className="login-field">
            <span>유효 시간 (분)</span>
            <input
              type="number"
              inputMode="numeric"
              min="1"
              step="1"
              value={expiresInMinutes}
              onChange={(event) => setExpiresInMinutes(event.target.value)}
              placeholder="예: 30"
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
