import { ApiError } from './api/client'
import type { RulesetItem, YaraRuleItem } from './api/rulesets'

// 서버 업로드 상한(1MiB)과 같다.
const MAX_RULESET_BYTES = 1_048_576

// 활성 이력이 있는 비활성 버전은 이전, 한 번도 적용된 적 없으면 대기다.
export function rulesetStatus(item: RulesetItem): '적용 중' | '대기' | '이전' {
  if (item.isActive) return '적용 중'
  return item.activatedAt ? '이전' : '대기'
}

// 서버 검증(확장자, 빈 파일, 크기)과 같은 조건을 먼저 확인해 불필요한 업로드를 줄인다.
export function validateRulesetFile(file: { name: string, size: number }) {
  if (!file.name.toLowerCase().endsWith('.yar')) return '.yar 파일만 업로드할 수 있습니다.'
  if (file.size === 0) return '빈 파일은 업로드할 수 없습니다.'
  if (file.size > MAX_RULESET_BYTES) return '파일이 너무 큽니다. 1MB 이하의 룰셋만 올릴 수 있습니다.'
  return ''
}

// 400·422는 컴파일 오류 줄 번호 같은 서버 메시지가 원인을 가장 잘 설명하므로 그대로 보여준다.
export function rulesetErrorMessage(cause: unknown) {
  if (cause instanceof ApiError) {
    if (cause.status === 400 || cause.status === 422) {
      const message = (cause.body as { message?: unknown } | null)?.message
      if (typeof message === 'string' && message) return message
    }
    if (cause.status === 503) return '탐지 엔진에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
    if (cause.status === 404) return '룰셋을 찾을 수 없습니다. 목록을 새로고침해 주세요.'
  }
  return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 대기 버전은 아직 엔진에 적용되지 않았으므로 자유롭게 끈다. 활성 버전만 확인을 받는다.
export function disableConfirmMessage(rules: YaraRuleItem[], target: YaraRuleItem, isActiveVersion: boolean) {
  if (!isActiveVersion) return null
  const otherEnabled = rules.some((rule) => rule.ruleId !== target.ruleId && rule.enabled)
  if (!otherEnabled) return '마지막 룰을 끄면 YARA 탐지가 꺼집니다. 계속할까요?'
  return '적용 중인 룰셋이 바뀌어 기존 엔진 판정이 다시 검사됩니다. 계속할까요?'
}
