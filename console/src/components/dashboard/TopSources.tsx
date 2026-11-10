import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { TopSource } from '../../lib/api/dashboard'
import { BarList } from './BarList'

type SourceBy = 'AGENT' | 'HOST'
type Props = { agents: TopSource[] | null; hosts: TopSource[] | null }

const TOGGLES: { value: SourceBy; label: string }[] = [
  { value: 'AGENT', label: 'PC' },
  { value: 'HOST', label: '도메인' },
]

// 차단 상위 PC·도메인. 두 목록을 미리 받아 두고 전환만 한다(다시 불러오지 않음).
export function TopSources({ agents, hosts }: Props) {
  const [by, setBy] = useState<SourceBy>('AGENT')
  const rows = by === 'AGENT' ? agents : hosts

  return (
    <section className="dash-panel dash-top" aria-labelledby="top-sources-title">
      <div className="dash-panel__header">
        <h2 id="top-sources-title">차단 상위</h2>
        <div className="dash-toggle" role="group" aria-label="차단 상위 기준">
          {TOGGLES.map((t) => (
            <button key={t.value} type="button" aria-pressed={by === t.value} onClick={() => setBy(t.value)}>
              {t.label}
            </button>
          ))}
        </div>
        {by === 'AGENT' && (
          <Link className="dash-panel__link" to="/agents">
            PC 현황 →
          </Link>
        )}
      </div>
      {rows && rows.length === 0 && <p className="dash-panel__empty">이 기간에 차단이 없습니다</p>}
      {rows && rows.length > 0 && (
        <BarList items={rows.map((row) => ({ key: row.key, label: row.label, count: row.blockCount }))} />
      )}
    </section>
  )
}
