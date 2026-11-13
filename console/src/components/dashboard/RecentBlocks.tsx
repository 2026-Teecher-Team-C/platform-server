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
  SIMILARITY: 'TLSH 유사도',
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
          <table className="dash-table--recent">
            <thead>
              <tr>
                <th scope="col">시각</th>
                <th scope="col">PC</th>
                <th scope="col">파일</th>
                <th scope="col">차단 사유</th>
                <th scope="col">심각도</th>
                <th scope="col">판정 출처</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((item) => (
                <tr key={item.eventId} className={newIds.has(item.eventId) ? 'is-new' : undefined}>
                  <td className="dash-cell-time">{`${formatMonthDay(item.createdAt)} ${formatClock(item.createdAt)}`}</td>
                  <td>{item.hostname}</td>
                  {/* 집계 화면에서도 이벤트 당시 TLSH 근거로 들어갈 수 있도록 같은 상세 경로를 사용한다. */}
                  <td className="dash-cell-clip" title={fileCell(item)}><Link to={'/events/' + encodeURIComponent(item.eventId)}>{fileCell(item)}</Link></td>
                  <td className="dash-cell-clip" title={blockReasonText(item)}>{blockReasonText(item)}</td>
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
