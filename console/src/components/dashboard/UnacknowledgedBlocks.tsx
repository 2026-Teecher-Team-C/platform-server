import { useState } from 'react'
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

export function UnacknowledgedBlocks({ page, loaded }: Props) {
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
    <section className="dash-panel" aria-labelledby="unack-blocks-title">
      <div className="dash-panel__header">
        <h2 id="unack-blocks-title">확인 안 한 높은 심각도 차단</h2>
        <span className="dash-badge">{count ?? '—'}</span>
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
  )
}
