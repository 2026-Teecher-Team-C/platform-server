import { useState } from 'react'
import type { FormEvent } from 'react'
import { validateBlacklistForm } from '../../lib/blacklist'
import { ClearableHashInput } from './ClearableHashInput'

export function BlacklistCreateForm({ busy, error, onCreate, onClearError }: {
  busy: boolean; error: string; onCreate: (sha256: string, reason: string) => Promise<boolean>
  onClearError: () => void
}) {
  const [sha256, setSha256] = useState('')
  const [reason, setReason] = useState('')
  const [validation, setValidation] = useState('')

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const invalid = validateBlacklistForm(sha256, reason)
    setValidation(invalid)
    if (invalid) return
    // 실패하면 입력을 남겨 수정/재시도할 수 있게 하고, 서버 성공이 확인된 경우에만 폼을 비운다.
    if (await onCreate(sha256.trim().toLowerCase(), reason.trim())) {
      setSha256('')
      setReason('')
    }
  }

  return <form className="events-filters blacklist-create" aria-label="블랙리스트 등록" onSubmit={submit}>
    <label>SHA-256<ClearableHashInput ariaLabel="등록할 SHA-256" clearLabel="등록 SHA-256 지우기" value={sha256} disabled={busy} onChange={(value) => { setSha256(value); setValidation(''); onClearError() }} placeholder="64자리 SHA-256" /></label>
    <label>등록 사유<input aria-label="등록 사유" value={reason} disabled={busy} onChange={(event) => { setReason(event.target.value); setValidation(''); onClearError() }} placeholder="차단이 필요한 이유" /></label>
    <button className="events-button events-button--primary" type="submit" disabled={busy}>등록</button>
    {(validation || error) && <p className="events-filter-error" role="alert">{validation || error}</p>}
  </form>
}
