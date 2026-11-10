import type { TopSource } from '../../lib/api/dashboard'

type Props = { rows: TopSource[] | null }

export function TopAgents({ rows }: Props) {
  const max = rows ? Math.max(0, ...rows.map((r) => r.blockCount)) : 0
  return (
    <section className="dash-panel dash-top" aria-labelledby="top-agents-title">
      <div className="dash-panel__header">
        <h2 id="top-agents-title">차단 상위 PC</h2>
      </div>
      {rows && rows.length === 0 && <p className="dash-panel__empty">이 기간에 차단이 없습니다</p>}
      {rows && rows.length > 0 && (
        <ul className="dash-top__list">
          {rows.map((row) => (
            <li key={row.key} className="dash-top__row">
              <span className="dash-top__label">{row.label}</span>
              <span className="dash-top__track">
                <span
                  className="dash-top__bar"
                  style={{ width: `${max > 0 ? (row.blockCount / max) * 100 : 0}%` }}
                />
              </span>
              <span className="dash-top__count">{row.blockCount}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
