import type { DailyBlocks, StackBy } from '../../lib/api/dashboard'

const EMPTY_TEXT = '이 기간에 차단이 없습니다'

const KEY_LABEL: Record<string, string> = {
  ENGINE: '엔진',
  CACHE: '판정 이력',
  BLACKLIST: '블랙리스트',
  FAIL_CLOSE: '장애 차단',
  OTHER: '기타',
}

const TOGGLES: { value: StackBy; label: string }[] = [
  { value: 'SOURCE', label: '판정 소스별' },
  { value: 'RULE', label: '룰별' },
]

// 'YYYY-MM-DD' 달력 날짜를 M/D로 바꾼다. Date를 거치면 시간대 때문에 하루가 밀릴 수 있다.
function monthDay(date: string): string {
  const [, m, d] = date.split('-')
  return `${Number(m)}/${Number(d)}`
}

type Props = {
  data: DailyBlocks | null
  stackBy: StackBy
  onStackByChange: (s: StackBy) => void
}

export function DailyBlocksChart({ data, stackBy, onStackByChange }: Props) {
  const totals = data
    ? data.days.map((day) => data.keys.reduce((sum, key) => sum + (day.counts[key] ?? 0), 0))
    : []
  const max = Math.max(0, ...totals)
  const empty = data !== null && max === 0

  return (
    <section className="dash-panel dash-daily" aria-labelledby="daily-blocks-title">
      <div className="dash-panel__header">
        <h2 id="daily-blocks-title">일별 차단</h2>
        <div className="dash-toggle" role="group" aria-label="쌓기 기준">
          {TOGGLES.map((t) => (
            <button
              key={t.value}
              type="button"
              aria-pressed={stackBy === t.value}
              onClick={() => onStackByChange(t.value)}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>
      {data && empty && <p className="dash-panel__empty">{EMPTY_TEXT}</p>}
      {data && !empty && (
        <>
          <div className="dash-daily__bars">
            {data.days.map((day, i) => {
              const total = totals[i]
              return (
                <div key={day.date} className="dash-daily__col">
                  <div
                    className="dash-daily__bar"
                    style={{ height: `${(total / max) * 100}%` }}
                    aria-label={`${monthDay(day.date)} 차단 ${total}건`}
                  >
                    {data.keys.map((key, k) => {
                      const count = day.counts[key] ?? 0
                      if (count === 0) return null
                      return (
                        <div
                          key={key}
                          className={`dash-daily__seg dash-daily__seg--${k}`}
                          style={{ height: `${(count / total) * 100}%` }}
                        />
                      )
                    })}
                  </div>
                  <span className="dash-daily__label">{monthDay(day.date)}</span>
                </div>
              )
            })}
          </div>
          <ul className="dash-daily__legend">
            {data.keys.map((key, k) => (
              <li key={key}>
                <span className={`dash-daily__swatch dash-daily__seg--${k}`} aria-hidden="true" />
                {KEY_LABEL[key] ?? key}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
