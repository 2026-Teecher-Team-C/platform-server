import { Link } from 'react-router-dom'
import type { ThreatRow } from '../../lib/api/dashboard'
import { SEVERITY_LABEL, severityTone } from '../../lib/dashboard/format'
import { topRules } from '../../lib/dashboard/summarize'
import { BarList } from './BarList'

type Props = { rows: ThreatRow[] | null }

// 매칭 룰 Top 5. 위협별 현황 데이터에서 룰만 골라 차단 수 순으로 막대를 그린다.
export function TopRules({ rows }: Props) {
  const top = rows ? topRules(rows, 5) : null

  return (
    <section className="dash-panel dash-top" aria-labelledby="top-rules-title">
      <div className="dash-panel__header">
        <h2 id="top-rules-title">매칭 룰 Top 5</h2>
        <Link className="dash-panel__link" to="/rulesets">
          YARA 룰셋 →
        </Link>
      </div>
      {top && top.length === 0 && <p className="dash-panel__empty">이 기간에 매칭된 룰이 없습니다</p>}
      {top && top.length > 0 && (
        <BarList
          items={top.map((row) => ({
            key: row.ruleName ?? '',
            label: row.ruleName ?? '',
            count: row.blockCount,
            badge: (
              <span className={`severity-badge severity-badge--${severityTone(row.severity)}`}>
                {SEVERITY_LABEL[row.severity]}
              </span>
            ),
          }))}
        />
      )}
    </section>
  )
}
