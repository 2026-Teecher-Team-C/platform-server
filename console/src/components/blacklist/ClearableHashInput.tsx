import { useRef } from 'react'

export function ClearableHashInput({ value, onChange, disabled, ariaLabel, clearLabel, placeholder, pattern, maxLength, title }: {
  value: string; onChange: (value: string) => void; disabled: boolean
  ariaLabel: string; clearLabel: string; placeholder?: string; pattern?: string; maxLength?: number; title?: string
}) {
  const input = useRef<HTMLInputElement>(null)
  return <span className="blacklist-hash-field">
    <input ref={input} aria-label={ariaLabel} value={value} onChange={(event) => onChange(event.target.value)} disabled={disabled} placeholder={placeholder} pattern={pattern} maxLength={maxLength} title={title} />
    {/* 폼을 제출하지 않고 해시만 비운 뒤 바로 다시 입력할 수 있도록 포커스를 돌려준다. */}
    <button className="blacklist-hash-clear" type="button" aria-label={clearLabel} title={clearLabel} disabled={disabled || !value} onClick={() => { onChange(''); input.current?.focus() }}>×</button>
  </span>
}
