import { useState } from 'react'
import type { FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../app/useAuth'
import { ApiError } from '../lib/api/client'

type LoginLocationState = {
  from?: {
    pathname?: string
  }
}

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { isAuthenticated, isInitializing, signIn } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')

  if (!isInitializing && isAuthenticated) {
    return <Navigate to="/dashboard" replace />
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (!username.trim() || !password) {
      setErrorMessage('아이디와 비밀번호를 모두 입력해 주세요.')
      return
    }

    setIsSubmitting(true)
    setErrorMessage('')

    try {
      await signIn({
        username: username.trim(),
        password,
      })

      const state = location.state as LoginLocationState | null
      const destination = state?.from?.pathname ?? '/dashboard'
      navigate(destination, { replace: true })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setErrorMessage('아이디 또는 비밀번호가 올바르지 않습니다.')
      } else if (error instanceof ApiError && error.status === 403) {
        setErrorMessage('비활성화된 계정이거나 접근 권한이 없습니다.')
      } else {
        setErrorMessage('로그인 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.')
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <div className="login-shell">
        <section className="login-intro" aria-label="서비스 소개">
          <div className="login-brand">
            <span className="login-brand-mark">T</span>
            <div>
              <strong>Teecher</strong>
              <span>Security Console</span>
            </div>
          </div>

          <div className="login-intro-copy">
            <p className="login-kicker">Team C · Admin Console</p>
            <h1>다운로드 보안 상태를 한곳에서 관리합니다.</h1>
            <p>
              다운로드 이벤트, 차단 현황, 관리 대상 PC와 보안 정책을 확인할 수 있는 관리자
              콘솔입니다.
            </p>
          </div>

          <p className="login-intro-footnote">Authorized administrators only</p>
        </section>

        <section className="login-form-panel">
          <form className="login-card" onSubmit={handleSubmit} noValidate>
            <div className="login-card-heading">
              <p className="eyebrow">관리자 로그인</p>
              <h2>관리 콘솔에 로그인</h2>
              <p>발급받은 관리자 계정으로 로그인해 주세요.</p>
            </div>

            <div className="login-form-fields">
              <label className="login-field">
                <span>아이디</span>
                <input
                  type="text"
                  name="username"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  autoComplete="username"
                  placeholder="아이디를 입력하세요"
                  disabled={isSubmitting}
                  autoFocus
                />
              </label>

              <label className="login-field">
                <span>비밀번호</span>
                <input
                  type="password"
                  name="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete="current-password"
                  placeholder="비밀번호를 입력하세요"
                  disabled={isSubmitting}
                />
              </label>
            </div>

            {errorMessage ? (
              <p className="login-error" role="alert">
                {errorMessage}
              </p>
            ) : null}

            <button className="login-submit" type="submit" disabled={isSubmitting}>
              {isSubmitting ? '로그인 중...' : '로그인'}
            </button>
          </form>
        </section>
      </div>
    </main>
  )
}
