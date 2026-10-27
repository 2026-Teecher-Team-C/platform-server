import { describe, expect, it } from 'vitest'
import { cacheHitLabel, decisionBadge } from './decision'

describe('decisionBadge', () => {
  it('판정 전 이벤트는 대기 상태로 표시한다', () => {
    expect(decisionBadge(null)).toEqual({ text: '판정 대기', tone: 'pending' })
  })

  it('판정 완료 이벤트는 기존 판정값과 배지 색상을 유지한다', () => {
    expect(decisionBadge('BLOCKED')).toEqual({ text: 'BLOCKED', tone: 'blocked' })
  })
})

describe('cacheHitLabel', () => {
  it('판정 전 이벤트는 아직 캐시 미스라고 단정하지 않는다', () => {
    expect(cacheHitLabel(null, false)).toBe('—')
  })

  it('판정 완료 이벤트에는 실제 캐시 결과를 표시한다', () => {
    expect(cacheHitLabel('BLOCKED', false)).toBe('Miss')
    expect(cacheHitLabel('BLOCKED', true)).toBe('Hit')
  })
})
