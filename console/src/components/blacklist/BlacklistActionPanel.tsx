import { useEffect, useRef } from 'react'
import type { BlacklistItem } from '../../lib/api/hashlists'

export type BlacklistSelection = { item: BlacklistItem; mode: 'edit' | 'remove' | 'activate' }

export function BlacklistActionPanel({ selection, reason, busy, error, onReasonChange, onConfirm, onCancel }: {
  selection: BlacklistSelection; reason: string; busy: boolean; error: string
  onReasonChange: (value: string) => void; onConfirm: () => void; onCancel: () => void
}) {
  const editing = selection.mode === 'edit'
  const activating = selection.mode === 'activate'
  const panel = useRef<HTMLElement>(null)
  useEffect(() => {
    // 표 아래쪽에서 눌러도 확인 내용을 볼 수 있게 이동한다. 위험한 확인 버튼 자체에는 자동 포커스하지 않는다.
    if (!editing) panel.current?.focus()
  }, [editing, selection.mode, selection.item.hashValue])
  return <section ref={panel} tabIndex={-1} className="blacklist-action-panel" aria-label={editing ? '블랙리스트 사유 수정' : activating ? '블랙리스트 활성화 확인' : '블랙리스트 비활성화 확인'}>
    <h2>{editing ? '사유 수정' : activating ? '블랙리스트 활성화' : '블랙리스트 비활성화'}</h2>
    <code className="blacklist-selected-hash">{selection.item.hashValue}</code>
    {/* 재활성화는 기존 등록 API를 사용한다. 서버가 바꾸는 심각도/출처를 숨기지 않고 확인받는다. */}
    {activating && <p>이 해시를 다시 차단 대상으로 활성화합니다. 심각도는 HIGH, 출처는 수동 등록으로 변경됩니다. 최초 등록 시각은 유지됩니다.</p>}
    {editing || activating ? <form onSubmit={(event) => { event.preventDefault(); if (!busy) onConfirm() }}>
      <label>{activating ? '활성화 사유' : '수정할 사유'}<input autoFocus={editing} aria-label={activating ? '활성화 사유' : '수정할 사유'} value={reason} disabled={busy} onChange={(event) => onReasonChange(event.target.value)} /></label>
      <div className="blacklist-actions"><button className="events-button events-button--primary" type="submit" disabled={busy}>{activating ? '활성화 확인' : '저장'}</button>
        <button className="events-button" type="button" disabled={busy} onClick={onCancel}>취소</button></div>
    </form> : <>
      {/* 해제는 예외 허용과 다르므로 사용자가 영향 범위를 확인한 다음에만 DELETE를 요청한다. */}
      <p>이 해시를 비활성화해 블랙리스트에서 해제할까요?</p>
      <p>해제해도 다운로드가 자동 허용되지는 않습니다. 이후 검사 결과에 따라 다시 차단될 수 있습니다.</p>
      <div className="blacklist-actions"><button className="events-button blacklist-danger" type="button" disabled={busy} onClick={onConfirm}>비활성화 확인</button>
        <button className="events-button" type="button" disabled={busy} onClick={onCancel}>취소</button></div>
    </>}
    {error && <p className="events-filter-error" role="alert">{error}</p>}
  </section>
}
