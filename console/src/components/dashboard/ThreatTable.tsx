import { Link } from 'react-router-dom'
import type { EngineStatus, ThreatRow } from '../../lib/api/dashboard'
import {
  SEVERITY_LABEL,
  formatClock,
  formatMonthDay,
  formatRelative,
  severityTone,
} from '../../lib/dashboard/format'
import { EngineStatusBadge } from './EngineStatusBadge'

type Props = { rows: ThreatRow[] | null; engine: EngineStatus | null; stale: boolean; now: Date }

const MAX_ROWS = 10

export function ThreatTable({ rows, engine, stale, now }: Props) {
  const showDown = !stale && engine?.state === 'DOWN'
  // 매칭 룰 Top 5용으로 더 받아 오므로 표에는 최근 위협 10줄만 둔다.
  const shown = rows ? rows.slice(0, MAX_ROWS) : null
  const maxBlocks = shown ? Math.max(0, ...shown.map((r) => r.blockCount)) : 0

  return (
    <section className="dash-panel" aria-labelledby="threat-table-title">
      <div className="dash-panel__header">
        <h2 id="threat-table-title">위협별 현황</h2>
        <EngineStatusBadge status={engine} stale={stale} now={now} />
      </div>
      {showDown && engine && (
        <p className="dash-panel__warning" role="alert">
          {`새 파일 다운로드가 장애 차단되고 있습니다 · 장애 이후 ${engine.failCloseSinceDown}건 · 영향 PC ${engine.affectedAgentsSinceDown}대 · ${
            engine.lastSuccessAt ? `마지막 정상 판정 ${formatClock(engine.lastSuccessAt)}` : '정상 판정 기록 없음'
          }`}{' '}
          <Link className="dash-panel__link" to="/events?decision=FAIL_CLOSE">
            장애 차단 목록 →
          </Link>
        </p>
      )}
      {shown && shown.length === 0 && <p className="dash-panel__empty">이 기간에 차단된 위협이 없습니다</p>}
      {shown && shown.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">위협 (룰)</th>
                <th scope="col">심각도</th>
                <th scope="col">영향 PC</th>
                <th scope="col">차단</th>
                <th scope="col">처음</th>
                <th scope="col">마지막</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((r, i) => (
                <tr key={`${r.kind}:${r.ruleName ?? ''}:${i}`}>
                  <td>{r.kind === 'BLACKLIST' ? '관리자 등록 블랙리스트' : (r.ruleName ?? '룰 정보 없음')}</td>
                  <td>
                    <span className={`severity-badge severity-badge--${severityTone(r.severity)}`}>
                      {SEVERITY_LABEL[r.severity]}
                    </span>
                  </td>
                  <td>{r.affectedAgents}</td>
                  <td>
                    <span className="dash-inline-bar">
                      <span className="dash-inline-bar__track" aria-hidden="true">
                        <span
                          className="dash-inline-bar__fill"
                          style={{ width: `${maxBlocks > 0 ? (r.blockCount / maxBlocks) * 100 : 0}%` }}
                        />
                      </span>
                      {r.blockCount}
                    </span>
                  </td>
                  <td>{formatMonthDay(r.firstSeenAt)}</td>
                  <td>{formatRelative(r.lastSeenAt, now)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
