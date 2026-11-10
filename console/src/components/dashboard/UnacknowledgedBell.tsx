import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { acknowledgeDownloadEvent, type UnacknowledgedBlock } from '../../lib/api/dashboard'
import type { PageResponse } from '../../lib/api/types'
import {
  SEVERITY_LABEL,
  blockReasonText,
  formatClock,
  formatMonthDay,
  severityTone,
} from '../../lib/dashboard/format'

const VIEW_ALL_HREF = '/events?decision=BLOCKED&minSeverity=HIGH&acknowledged=false'
const ERROR_TEXT = '확인 처리에 실패했습니다. 다시 시도해 주세요.'

type Props = { page: PageResponse<UnacknowledgedBlock> | null; loaded: boolean }

const TITLE = '확인 안 한 높은 심각도 차단'

// 대시보드 헤더의 종 버튼. 배지는 미확인 High·Critical 차단 수이고, 누르면 목록 패널이 열린다.
// 패널은 닫혀 있을 때도 마운트해 둔다 — 확인 처리 중인 상태와 방금 확인한 행 숨김이 열고 닫아도 유지된다.
export function UnacknowledgedBell({ page, loaded }: Props) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const panelId = useId()
  const [hiddenIds, setHiddenIds] = useState<ReadonlySet<string>>(new Set())
  const [pendingIds, setPendingIds] = useState<ReadonlySet<string>>(new Set())
  const [failed, setFailed] = useState(false)
  const [seenPage, setSeenPage] = useState(page)

  // 새 page가 들어오면 더 이상 목록에 없는 id는 숨김 목록에서 지운다.
  if (page !== seenPage) {
    setSeenPage(page)
    if (page && hiddenIds.size > 0) {
      const present = new Set(page.content.map((item) => item.eventId))
      const kept = [...hiddenIds].filter((id) => present.has(id))
      if (kept.length !== hiddenIds.size) setHiddenIds(new Set(kept))
    }
  }

  const content = page?.content ?? []
  const visible = content.filter((item) => !hiddenIds.has(item.eventId))
  const hiddenCount = content.length - visible.length
  const count = loaded && page ? Math.max(0, page.totalElements - hiddenCount) : null
  const empty = count === 0

  useEffect(() => {
    if (!open) {
      return
    }
    function onMouseDown(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false)
        buttonRef.current?.focus()
      }
    }
    document.addEventListener('mousedown', onMouseDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onMouseDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  async function acknowledge(eventId: string) {
    setPendingIds((prev) => new Set(prev).add(eventId))
    try {
      await acknowledgeDownloadEvent(eventId)
      setHiddenIds((prev) => new Set(prev).add(eventId))
      setFailed(false)
    } catch {
      setFailed(true)
    } finally {
      setPendingIds((prev) => {
        const next = new Set(prev)
        next.delete(eventId)
        return next
      })
    }
  }

  return (
    <div className="bell" ref={rootRef}>
      <button
        ref={buttonRef}
        className="bell-button"
        type="button"
        aria-label={count === null ? TITLE : `${TITLE} ${count}건`}
        title={TITLE}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((prev) => !prev)}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
          strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
        {count !== null && count > 0 && <span className="bell-badge">{count}</span>}
      </button>
      <section id={panelId} className="dash-panel bell-panel" hidden={!open} aria-labelledby="unack-blocks-title">
        <div className="dash-panel__header">
          <h2 id="unack-blocks-title">{TITLE}</h2>
          <span className="dash-panel__sub">High · Critical</span>
          {count !== null && count > 0 && (
            <Link className="dash-panel__link" to={VIEW_ALL_HREF}>
              전체 보기 →
            </Link>
          )}
        </div>
        {failed && (
          <p className="dash-panel__error" role="alert">
            {ERROR_TEXT}
          </p>
        )}
        {loaded && empty && <p className="dash-panel__empty">확인할 차단이 없습니다</p>}
        {loaded && !empty && visible.length > 0 && (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">시각</th>
                  <th scope="col">PC</th>
                  <th scope="col">파일</th>
                  <th scope="col">차단 사유</th>
                  <th scope="col">심각도</th>
                  <th scope="col">처리</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((item) => {
                  const pending = pendingIds.has(item.eventId)
                  return (
                    <tr key={item.eventId}>
                      <td>{`${formatMonthDay(item.createdAt)} ${formatClock(item.createdAt)}`}</td>
                      <td>{item.hostname}</td>
                      <td className="dash-cell-clip" title={item.filename ?? undefined}>{item.filename ?? '—'}</td>
                      <td className="dash-cell-clip" title={blockReasonText(item)}>{blockReasonText(item)}</td>
                      <td>
                        <span className={`severity-badge severity-badge--${severityTone(item.severity)}`}>
                          {SEVERITY_LABEL[item.severity]}
                        </span>
                      </td>
                      <td>
                        <button
                          type="button"
                          disabled={pending}
                          aria-label={`${item.hostname} ${item.filename ?? ''} 차단 확인`.trim()}
                          onClick={() => void acknowledge(item.eventId)}
                        >
                          {pending ? '처리 중' : '확인'}
                        </button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}
