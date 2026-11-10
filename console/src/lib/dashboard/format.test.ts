import { describe, expect, it } from 'vitest'
import {
  PERIOD_OPTIONS,
  SEVERITY_LABEL,
  blockReasonText,
  formatClock,
  formatDelta,
  formatMonthDay,
  formatRate,
  formatRelative,
  periodRange,
  previousPeriodLabel,
  severityTone,
} from './format'
import type { UnacknowledgedBlock } from '../api/dashboard'
import type { DownloadEventListItem } from '../api/events'

const now = new Date(2026, 10, 8, 14, 2, 30)
const at = (...args: [number, number, number, number, number]) => new Date(...args).toISOString()

describe('기간', () => {
  it('선택지는 오늘/최근 7일/최근 30일이다', () => {
    expect(PERIOD_OPTIONS).toEqual([
      { key: 'today', label: '오늘' },
      { key: '7d', label: '최근 7일' },
      { key: '30d', label: '최근 30일' },
    ])
  })

  it('today의 from은 로컬 0시, to는 now다', () => {
    const r = periodRange('today', now)
    expect(r.from).toBe(new Date(2026, 10, 8, 0, 0, 0, 0).toISOString())
    expect(r.to).toBe(now.toISOString())
  })

  it('7d/30d는 now에서 N일 전부터다', () => {
    expect(periodRange('7d', now).from).toBe(new Date(now.getTime() - 7 * 86400000).toISOString())
    expect(periodRange('30d', now).from).toBe(new Date(now.getTime() - 30 * 86400000).toISOString())
    expect(periodRange('7d', now).to).toBe(now.toISOString())
  })

  it('비교 문구', () => {
    expect(previousPeriodLabel('today')).toBe('직전 같은 시간 대비')
    expect(previousPeriodLabel('7d')).toBe('전주 대비')
    expect(previousPeriodLabel('30d')).toBe('이전 30일 대비')
  })
})

describe('숫자 문구', () => {
  it('formatDelta', () => {
    expect(formatDelta(48, 36)).toBe('+12')
    expect(formatDelta(3, 6)).toBe('−3')
    expect(formatDelta(3, 3)).toBe('변화 없음')
  })

  it('formatRate', () => {
    expect(formatRate(0.025)).toBe('2.5%')
    expect(formatRate(0)).toBe('0.0%')
    expect(formatRate(NaN)).toBe('0%')
    expect(formatRate(Infinity)).toBe('0%')
  })
})

describe('심각도', () => {
  it('라벨', () => {
    expect(SEVERITY_LABEL).toEqual({
      CRITICAL: 'Critical',
      HIGH: 'High',
      MEDIUM: 'Medium',
      LOW: 'Low',
    })
  })

  it('톤', () => {
    expect(severityTone('CRITICAL')).toBe('danger')
    expect(severityTone('HIGH')).toBe('danger')
    expect(severityTone('MEDIUM')).toBe('warning')
    expect(severityTone('LOW')).toBe('neutral')
    expect(severityTone(null)).toBe('neutral')
  })
})

describe('시각', () => {
  it('formatRelative: 0분은 방금', () => {
    expect(formatRelative(at(2026, 10, 8, 14, 2), now)).toBe('방금')
  })

  it('formatRelative: 59분 전', () => {
    expect(formatRelative(new Date(now.getTime() - 59 * 60000).toISOString(), now)).toBe('59분 전')
  })

  it('formatRelative: 오늘 1시간 이상 전은 HH:MM', () => {
    expect(formatRelative(at(2026, 10, 8, 9, 5), now)).toBe('09:05')
  })

  it('formatRelative: 어제', () => {
    expect(formatRelative(at(2026, 10, 7, 23, 50), now)).toBe('어제')
  })

  it('formatRelative: 그 전은 M/D', () => {
    expect(formatRelative(at(2026, 10, 4, 10, 0), now)).toBe('11/4')
  })

  it('formatRelative: 미래는 방금', () => {
    expect(formatRelative(at(2026, 10, 8, 14, 10), now)).toBe('방금')
  })

  it('formatMonthDay / formatClock', () => {
    expect(formatMonthDay(at(2026, 10, 4, 3, 7))).toBe('11/4')
    expect(formatClock(at(2026, 10, 4, 3, 7))).toBe('03:07')
    expect(formatClock(at(2026, 10, 4, 15, 30))).toBe('15:30')
  })
})

describe('blockReasonText', () => {
  it('FAIL_CLOSE는 검사 불가', () => {
    expect(
      blockReasonText({ decision: 'FAIL_CLOSE', topRuleName: null, extraRuleCount: 0 }),
    ).toBe('검사 불가')
  })

  it('블랙리스트(reasonType 또는 decisionSource)', () => {
    expect(
      blockReasonText({ reasonType: 'BLACKLIST', topRuleName: null, extraRuleCount: 0 }),
    ).toBe('관리자 등록 블랙리스트')
    expect(
      blockReasonText({ decisionSource: 'BLACKLIST', topRuleName: null, extraRuleCount: 0 }),
    ).toBe('관리자 등록 블랙리스트')
  })

  it('룰명 / 룰명 외 N건', () => {
    expect(blockReasonText({ topRuleName: 'Emotet', extraRuleCount: 0 })).toBe('Emotet')
    expect(blockReasonText({ topRuleName: 'Emotet', extraRuleCount: 2 })).toBe('Emotet 외 2건')
  })

  it('룰 정보 없음', () => {
    expect(blockReasonText({ decision: 'BLOCKED', topRuleName: null, extraRuleCount: 0 })).toBe(
      '룰 정보 없음',
    )
  })

  it('UnacknowledgedBlock과 DownloadEventListItem을 캐스팅 없이 받는다', () => {
    const block = {
      reasonType: 'RULE',
      topRuleName: 'A',
      extraRuleCount: 1,
    } as UnacknowledgedBlock
    const ev = {
      decision: 'BLOCKED',
      decisionSource: 'ENGINE',
      topRuleName: 'B',
      extraRuleCount: 0,
    } as DownloadEventListItem
    expect(blockReasonText(block)).toBe('A 외 1건')
    expect(blockReasonText(ev)).toBe('B')
  })
})
