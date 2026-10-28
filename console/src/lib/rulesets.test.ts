import { describe, expect, it } from 'vitest'
import { ApiError } from './api/client'
import type { RulesetItem, YaraRuleItem } from './api/rulesets'
import { disableConfirmMessage, rulesetErrorMessage, rulesetStatus, validateRulesetFile } from './rulesets'

const base: RulesetItem = {
  version: 3, ruleCount: 3, disabledRuleCount: 0, isActive: false,
  createdAt: '2026-10-28T00:00:00Z', activatedAt: null,
}

function rule(ruleId: string, enabled: boolean): YaraRuleItem {
  return { ruleId, ruleName: ruleId, severity: 'HIGH', enabled }
}

describe('rulesetStatus', () => {
  it('활성·이전·대기를 구분한다', () => {
    expect(rulesetStatus({ ...base, isActive: true, activatedAt: '2026-10-28T01:00:00Z' })).toBe('적용 중')
    expect(rulesetStatus({ ...base, activatedAt: '2026-10-27T00:00:00Z' })).toBe('이전')
    expect(rulesetStatus(base)).toBe('대기')
  })
})

describe('validateRulesetFile', () => {
  it('.yar 확장자, 1바이트 이상, 1MiB 이하만 통과한다', () => {
    expect(validateRulesetFile({ name: 'a.yar', size: 10 })).toBe('')
    expect(validateRulesetFile({ name: 'a.txt', size: 10 })).not.toBe('')
    expect(validateRulesetFile({ name: 'a.yar', size: 0 })).not.toBe('')
    expect(validateRulesetFile({ name: 'a.yar', size: 1_048_576 })).toBe('')
    expect(validateRulesetFile({ name: 'a.yar', size: 1_048_577 })).not.toBe('')
  })
})

describe('rulesetErrorMessage', () => {
  it('400·422는 서버 메시지를, 503은 엔진 안내를 보여준다', () => {
    expect(rulesetErrorMessage(new ApiError(422, { message: 'line 3: syntax error' }))).toBe('line 3: syntax error')
    expect(rulesetErrorMessage(new ApiError(400, { message: '빈 파일' }))).toBe('빈 파일')
    expect(rulesetErrorMessage(new ApiError(503, {}))).toBe('탐지 엔진에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.')
    expect(rulesetErrorMessage(new ApiError(404, {}))).toContain('찾을 수 없습니다')
    expect(rulesetErrorMessage(new Error('network'))).toBe('요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  })
})

describe('disableConfirmMessage', () => {
  const rules = [rule('a', true), rule('b', false)]

  it('대기 버전은 확인 없이 끈다', () => {
    expect(disableConfirmMessage(rules, rules[0], false)).toBeNull()
  })

  it('활성 버전의 마지막 켜진 룰은 탐지 중단을 경고한다', () => {
    expect(disableConfirmMessage(rules, rules[0], true)).toBe('마지막 룰을 끄면 YARA 탐지가 꺼집니다. 계속할까요?')
  })

  it('활성 버전의 일반 룰은 재검사를 경고한다', () => {
    const many = [rule('a', true), rule('b', true)]
    expect(disableConfirmMessage(many, many[0], true)).toBe('적용 중인 룰셋이 바뀌어 기존 엔진 판정이 다시 검사됩니다. 계속할까요?')
  })
})
