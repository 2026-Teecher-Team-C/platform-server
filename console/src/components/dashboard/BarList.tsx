import type { ReactNode } from 'react'

export type BarItem = { key: string; label: string; count: number; badge?: ReactNode }

// 이름 · 막대 · 숫자로 된 가로 막대 목록. 막대 길이는 목록에서 가장 큰 값 대비 비율이다.
export function BarList({ items }: { items: BarItem[] }) {
  const max = Math.max(0, ...items.map((item) => item.count))
  return (
    <ul className="dash-top__list">
      {items.map((item) => (
        <li key={item.key} className="dash-top__row">
          <span className="dash-top__name">
            <span className="dash-top__label" title={item.label}>{item.label}</span>
            {item.badge}
          </span>
          <span className="dash-top__track">
            <span className="dash-top__bar" style={{ width: `${max > 0 ? (item.count / max) * 100 : 0}%` }} />
          </span>
          <span className="dash-top__count">{item.count}</span>
        </li>
      ))}
    </ul>
  )
}
