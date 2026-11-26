import { useEffect, useRef } from 'react'
import type { AdminAppealDetail } from '../../lib/api/adminAppeals'
import { DECISION_NOTE_MAX, decisionNoteLength } from '../../lib/appealReview'

export type DecisionMode = 'none' | 'approve' | 'reject'

// QuarantineRestorePanel과 같은 확인 패턴: 버튼을 누르면 영향 설명이 펼쳐지고, 포커스는 확인 버튼이 아니라 설명으로 간다.
export function AppealDecisionPanel({ item, mode, note, disabled, busy, error, needsReload, notice, onStart, onCancel, onNoteChange, onConfirm, onReload }: {
  item: AdminAppealDetail; mode: DecisionMode; note: string; disabled: boolean; busy: boolean
  error: string; needsReload: boolean; notice: string
  onStart: (mode: Exclude<DecisionMode, 'none'>) => void; onCancel: () => void; onNoteChange: (note: string) => void
  onConfirm: () => void; onReload: () => void
}) {
  const form = useRef<HTMLFormElement>(null)
  useEffect(() => {
    if (mode !== 'none') form.current?.focus()
  }, [mode, item.appealId])

  const length = decisionNoteLength(note)
  const approving = mode === 'approve'
  // 승인 의견은 선택(비우면 "소명 승인"), 거절 의견은 필수다. 둘 다 1000자까지.
  const noteValid = approving ? length <= DECISION_NOTE_MAX : length >= 1 && length <= DECISION_NOTE_MAX

  return <section className="event-detail-card quarantine-restore-panel appeal-decision-panel" aria-label="소명 결정">
    <h2>검토 결정</h2>
    {item.status === 'PENDING' && (mode === 'none' ? (
      <div className="quarantine-restore-actions">
        <button className="events-button events-button--primary" type="button" disabled={disabled} onClick={() => onStart('approve')}>승인</button>
        <button className="events-button" type="button" disabled={disabled} onClick={() => onStart('reject')}>거절</button>
      </div>
    ) : (
      <form ref={form} tabIndex={-1} aria-label={approving ? '소명 승인 확인' : '소명 거절 확인'}
        onSubmit={(event) => { event.preventDefault(); if (!disabled && !needsReload && noteValid) onConfirm() }}>
        <p className="event-long-value">{item.filename || '파일명 없음'}</p>
        <code className="event-long-value event-hash">{item.sha256}</code>
        {approving ? <>
          <p className="appeal-decision-impact">이 해시를 화이트리스트에 등록합니다. <strong>회사 모든 PC에서 이 파일이 통과합니다.</strong></p>
          {item.blacklisted && <p className="appeal-decision-impact"><strong>블랙리스트에서 해제됩니다.</strong></p>}
        </> : (
          <p>거절 의견은 직원의 소명 페이지에 담당자 의견으로 그대로 보입니다.</p>
        )}
        <label>{approving ? '승인 의견 (선택)' : '거절 의견 (필수)'}
          <textarea aria-label={approving ? '승인 의견' : '거절 의견'} value={note} rows={3} disabled={disabled}
            placeholder={approving ? "비워두면 '소명 승인'으로 기록됩니다." : '예: 업무용으로 확인되지 않은 파일입니다.'}
            onChange={(event) => onNoteChange(event.target.value)} />
        </label>
        <p className={'appeal-decision-count' + (length > DECISION_NOTE_MAX ? ' is-over' : '')}>{length} / {DECISION_NOTE_MAX}</p>
        <div className="quarantine-restore-actions">
          <button className="events-button events-button--primary" type="submit" disabled={disabled || needsReload || !noteValid}>
            {busy ? '처리 중…' : approving ? '승인 확인' : '거절 확인'}
          </button>
          <button className="events-button" type="button" disabled={disabled} onClick={onCancel}>취소</button>
          {needsReload && <button className="events-button" type="button" disabled={disabled} onClick={onReload}>상태 새로고침</button>}
        </div>
        {error && <p className="events-filter-error" role="alert">{error}</p>}
      </form>
    ))}
    {notice && <p className="quarantine-restore-success" role="status">{notice}</p>}
  </section>
}
