import { Link } from 'react-router-dom'
import type { DownloadDecisionSource, DownloadEventListItem } from '../../lib/api/events'
import {
  SEVERITY_LABEL,
  blockReasonText,
  formatClock,
  formatMonthDay,
  severityTone,
} from '../../lib/dashboard/format'

const VIEW_ALL_HREF = '/events?decision=BLOCKED&decision=FAIL_CLOSE'

const SOURCE_LABELS: Record<DownloadDecisionSource, string> = {
  WHITELIST: '화이트리스트',
  BLACKLIST: '블랙리스트',
  CACHE: '판정 이력',
  ENGINE: '탐지 엔진',
  POLICY: '정책',
}

type Props = { rows: DownloadEventListItem[] | null; newIds: ReadonlySet<string> }

function fileCell(item: DownloadEventListItem): string {
  return item.filename ?? (item.sha256 ? `${item.sha256.slice(0, 12)}…` : '—')
}

export function RecentBlocks({ rows, newIds }: Props) {
  return (
    <section className="dash-panel" aria-labelledby="recent-blocks-title">
      <div className="dash-panel__header">
        <h2 id="recent-blocks-title">최근 차단</h2>
        <Link className="dash-panel__link" to={VIEW_ALL_HREF}>
          전체 →
        </Link>
      </div>
      {rows && rows.length === 0 && <p className="dash-panel__empty">이 기간에 차단이 없습니다</p>}
      {rows && rows.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>시각</th>
                <th>PC</th>
                <th>파일</th>
                <th>차단 사유</th>
                <th>심각도</th>
                <th>판정 출처</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((item) => (
                <tr key={item.eventId} className={newIds.has(item.eventId) ? 'is-new' : undefined}>
                  <td>{`${formatMonthDay(item.createdAt)} ${formatClock(item.createdAt)}`}</td>
                  <td>{item.hostname}</td>
                  <td>{fileCell(item)}</td>
                  <td>{blockReasonText(item)}</td>
                  <td>
                    {item.severity ? (
                      <span className={`severity-badge severity-badge--${severityTone(item.severity)}`}>
                        {SEVERITY_LABEL[item.severity]}
                      </span>
                    ) : (
                      '—'
                    )}
                  </td>
                  <td>{item.decisionSource ? SOURCE_LABELS[item.decisionSource] : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
