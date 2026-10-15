import { describe, expect, it } from 'vitest'
import { isAuthDisabled } from './authMode'

describe('isAuthDisabled', () => {
  it('disabled일 때만 인증을 끈다', () => {
    expect(isAuthDisabled('disabled')).toBe(true)
  })

  it('값이 없거나 다른 값이면 인증을 켠다', () => {
    expect(isAuthDisabled(undefined)).toBe(false)
    expect(isAuthDisabled('')).toBe(false)
    expect(isAuthDisabled('enabled')).toBe(false)
    expect(isAuthDisabled('DISABLED')).toBe(false)
  })
})
