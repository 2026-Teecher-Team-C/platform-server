export function BlacklistNotice({ message, onDismiss }: { message: string; onDismiss: () => void }) {
  if (!message) return null
  // 표의 행이나 검색 폼을 밀지 않는 고정 알림이다. 자동 닫기 타이머는 페이지에서 관리한다.
  return <div className="blacklist-notice" role="status" aria-live="polite" aria-atomic="true">
    <span>{message}</span>
    <button className="blacklist-notice-close" type="button" aria-label="알림 닫기" onClick={onDismiss}>×</button>
  </div>
}
