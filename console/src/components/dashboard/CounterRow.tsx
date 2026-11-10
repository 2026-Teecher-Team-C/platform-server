import type { DashboardSummary } from '../../lib/api/dashboard'
import { formatDelta, formatRate, previousPeriodLabel, type PeriodKey } from '../../lib/dashboard/format'

type Props = { summary: DashboardSummary | null; period: PeriodKey }

const n = (v: number) => v.toLocaleString('ko-KR')

export function CounterRow({ summary, period }: Props) {
  const counters: { label: string; value: string; sub: string | null }[] = [
    {
      label: '차단',
      value: summary ? n(summary.blocked) : '—',
      sub: summary
        ? `${previousPeriodLabel(period)} ${formatDelta(summary.blocked, summary.blockedPreviousPeriod)}`
        : null,
    },
    {
      label: '악성 판정',
      value: summary ? n(summary.malicious.total) : '—',
      sub: summary
        ? `엔진 ${n(summary.malicious.engine)} · 판정 이력 ${n(summary.malicious.cache)} · 블랙리스트 ${n(summary.malicious.blacklist)}`
        : null,
    },
    { label: '장애 차단', value: summary ? n(summary.failClose) : '—', sub: summary ? '검사 불가' : null },
    {
      label: '영향받은 PC',
      value: summary ? n(summary.affectedAgents) : '—',
      sub: summary ? `전체 ${n(summary.totalAgents)}대 중` : null,
    },
    {
      label: '검사한 다운로드',
      value: summary ? n(summary.inspected) : '—',
      sub: summary ? `차단율 ${formatRate(summary.blockRate)}` : null,
    },
  ]

  return (
    <div className="dash-counters">
      {counters.map((c) => (
        <div key={c.label} className="dash-counter">
          <div className="dash-counter__label">{c.label}</div>
          <div className="dash-counter__value">{c.value}</div>
          {c.sub && <div className="dash-counter__sub">{c.sub}</div>}
        </div>
      ))}
    </div>
  )
}
