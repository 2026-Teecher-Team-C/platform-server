import type { DailyBlocks, StackBy } from '../../lib/api/dashboard'

const EMPTY_TEXT = '이 기간에 차단이 없습니다'

const KEY_LABEL: Record<string, string> = {
  ENGINE: '엔진',
  CACHE: '판정 이력',
  BLACKLIST: '블랙리스트',
  FAIL_CLOSE: '장애 차단',
  OTHER: '기타',
}

// 색은 키 의미로 고정한다. 그래야 FAIL_CLOSE·BLACKLIST가 판정 소스별·룰별에서 같은 색으로 보인다.
const FIXED_TONE: Record<string, string> = {
  ENGINE: 'engine',
  CACHE: 'cache',
  BLACKLIST: 'blacklist',
  FAIL_CLOSE: 'fail-close',
  OTHER: 'other',
}
const RULE_TONES = 6

// 룰 키는 고정 키를 뺀 순번으로 팔레트를 돈다(팔레트는 고정 색을 쓰지 않는다).
function toneClasses(keys: string[]): string[] {
  let rule = 0
  return keys.map((key) => `dash-daily__seg--${FIXED_TONE[key] ?? `rule-${rule++ % RULE_TONES}`}`)
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
  const tones = data ? toneClasses(data.keys) : []

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
                  <div className="dash-daily__track">
                    <div
                      className="dash-daily__bar"
                      role="img"
                      style={{ height: `${(total / max) * 100}%` }}
                      aria-label={`${monthDay(day.date)} 차단 ${total}건`}
                    >
                      {data.keys.map((key, k) => {
                        const count = day.counts[key] ?? 0
                        if (count === 0) return null
                        return (
                          <div
                            key={key}
                            className={`dash-daily__seg ${tones[k]}`}
                            style={{ height: `${(count / total) * 100}%` }}
                          />
                        )
                      })}
                    </div>
                  </div>
                  <span className="dash-daily__label">{monthDay(day.date)}</span>
                </div>
              )
            })}
          </div>
          <ul className="dash-daily__legend">
            {data.keys.map((key, k) => (
              <li key={key}>
                <span className={`dash-daily__swatch ${tones[k]}`} aria-hidden="true" />
                {KEY_LABEL[key] ?? key}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
