import { useEffect, useRef } from 'react'
import type { QuarantineListItem } from '../../lib/api/quarantine'

export function QuarantineRestorePanel({ item, open, reason, disabled, busy, error, needsReload, notice, onStart, onCancel, onReasonChange, onConfirm, onReload }: {
  item: QuarantineListItem; open: boolean; reason: string; disabled: boolean; busy: boolean
  error: string; needsReload: boolean; notice: string
  onStart: () => void; onCancel: () => void; onReasonChange: (reason: string) => void; onConfirm: () => void; onReload: () => void
}) {
  const form = useRef<HTMLFormElement>(null)
  useEffect(() => {
    // 보안에 영향을 주는 확인 버튼 대신 설명 영역에 포커스를 옮겨 영향부터 읽게 한다.
    if (open) form.current?.focus()
  }, [open, item.quarantineId])

  return <section className="event-detail-card quarantine-restore-panel" aria-label="오탐 복원">
    <h2>오탐 복원</h2>
    {item.status !== 'QUARANTINED' ? <p>{item.status === 'RESTORED' ? '이미 복원 처리된 파일입니다.' : '현재 상태에서는 복원할 수 없습니다.'}</p>
      : !open ? <button className="events-button events-button--primary" type="button" disabled={disabled} onClick={onStart}>오탐으로 복원</button>
      : <form ref={form} tabIndex={-1} aria-label="격리 파일 오탐 복원 확인" onSubmit={(event) => { event.preventDefault(); if (!disabled && !needsReload) onConfirm() }}>
        <p>{item.originalFilename || '파일명 없음'}</p>
        <code className="event-long-value event-hash">{item.sha256}</code>
        <p>오탐으로 확인한 파일만 복원해 주세요. 이 해시를 화이트리스트에 등록하고, 같은 해시의 활성 블랙리스트는 해제합니다.</p>
        <p>파일을 PC로 다운로드하는 기능은 아닙니다.</p>
        <label>복원 사유 (선택)<textarea aria-label="복원 사유" value={reason} rows={3} disabled={disabled} placeholder="비워두면 '오탐 복원'으로 기록됩니다." onChange={(event) => onReasonChange(event.target.value)} /></label>
        <div className="quarantine-restore-actions">
          <button className="events-button events-button--primary" type="submit" disabled={disabled || needsReload}>{busy ? '복원 중…' : '복원 확인'}</button>
          <button className="events-button" type="button" disabled={disabled} onClick={onCancel}>취소</button>
          {needsReload && <button className="events-button" type="button" disabled={disabled} onClick={onReload}>상태 새로고침</button>}
        </div>
        {error && <p className="events-filter-error" role="alert">{error}</p>}
      </form>}
    {notice && <p className="quarantine-restore-success" role="status">{notice}</p>}
  </section>
}
