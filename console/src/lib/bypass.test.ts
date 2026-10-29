import { describe, expect, it } from 'vitest'
import { validateBypassInput } from './bypass'

describe('서버 BypassHost, category, reason 규칙', () => {
  it('앞뒤 ASCII 공백, 대문자, 마지막 점 하나만 정규화하고 사유는 보존한다', () => {
    expect(validateBypassInput({ host: ' UPDate.Example.COM. ', category: 'PINNED', reason: ' 사유 ' }))
      .toEqual({ host: 'update.example.com', category: 'PINNED', reason: ' 사유 ' })
  })
  it.each(['', '*.example.com', 'https://example.com', 'example.com/a', 'example.com:443', 'a@b.com', 'a.com?', 'a.com#x', 'localhost', '1.2.3.4', 'a..com', '-a.com', 'a-.com', '한글.com', 'a.com..', 'a'.repeat(64) + '.com', ('a'.repeat(63) + '.').repeat(4) + 'com'])('잘못된 host %s를 막는다', (host) => {
    expect(() => validateBypassInput({ host, category: 'PINNED', reason: '사유' })).toThrow()
  })
  it.each(['pinned', '', 'OTHER', ' PINNED'])('category는 정확한 서버 열거값만 허용한다: %s', (category) => {
    expect(() => validateBypassInput({ host: 'a.com', category, reason: '사유' })).toThrow()
  })
  it('사유는 Java isBlank 기준을 사용해 NBSP를 보존하고 전각 공백은 거절한다', () => {
    expect(validateBypassInput({ host: 'a.com', category: 'PINNED', reason: '\u00a0' }).reason).toBe('\u00a0')
    expect(() => validateBypassInput({ host: 'a.com', category: 'PINNED', reason: '\u3000' })).toThrow()
    expect(() => validateBypassInput({ host: 'a.com', category: 'PINNED', reason: '\u001c' })).toThrow()
  })

  it('빈 사유를 거절하고 임의의 길이 제한을 만들지 않는다', () => {
    expect(() => validateBypassInput({ host: 'a.com', category: 'PINNED', reason: ' \n ' })).toThrow()
    expect(validateBypassInput({ host: 'a.com', category: 'SECURITY_UPDATE', reason: 'a'.repeat(10000) }).reason).toHaveLength(10000)
  })
})
