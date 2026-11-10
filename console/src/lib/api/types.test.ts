import { describe, expect, it } from 'vitest'
import { toQueryString } from './types'

describe('toQueryString', () => {
  it('배열 값은 같은 키로 여러 번 붙인다', () => {
    expect(toQueryString({ decision: ['BLOCKED', 'FAIL_CLOSE'], size: 10 })).toBe(
      '?decision=BLOCKED&decision=FAIL_CLOSE&size=10',
    )
  })

  it('false는 버리지 않는다', () => {
    expect(toQueryString({ acknowledged: false })).toBe('?acknowledged=false')
  })

  it('0은 버리지 않는다', () => {
    expect(toQueryString({ page: 0 })).toBe('?page=0')
  })

  it('빈 배열과 빈 문자열은 버린다', () => {
    expect(toQueryString({ decision: [], from: '' })).toBe('')
  })

  it('undefined와 null은 버린다', () => {
    expect(toQueryString({ a: undefined, b: null, c: 'x' })).toBe('?c=x')
  })
})
