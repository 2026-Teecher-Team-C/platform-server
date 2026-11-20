import type { SimilarityPolicyLimits, UpdateSimilarityPolicyInput } from './api/policies'

// 입력창은 문자열이라 숫자로 바꾸기 전 값을 따로 둔다.
export type SimilarityPolicyDraft = { blockDistance: string; warnDistance: string }

// 서버의 SimilarityThresholds와 같은 규칙이다. 범위를 바꾸는 쪽은 서버이므로 숫자는 limits로 받는다.
//   차단: blockMin 이상 blockMax 이하
//   경고: 차단보다 크고 warnMax 이하 (경고가 차단과 같으면 WARN 구간이 한 칸도 없다)
// mock 핸들러도 이 함수를 써서 화면과 mock이 같은 규칙을 갖게 한다.
export function validateSimilarityRange(
  input: UpdateSimilarityPolicyInput,
  limits: SimilarityPolicyLimits,
): UpdateSimilarityPolicyInput {
  const { blockDistance, warnDistance } = input
  if (!Number.isInteger(blockDistance) || blockDistance < limits.blockMin || blockDistance > limits.blockMax) {
    throw new Error(`차단 임계값은 ${limits.blockMin} 이상 ${limits.blockMax} 이하여야 합니다.`)
  }
  if (!Number.isInteger(warnDistance) || warnDistance <= blockDistance || warnDistance > limits.warnMax) {
    throw new Error(`경고 임계값은 차단 임계값보다 크고 ${limits.warnMax} 이하여야 합니다.`)
  }
  return { blockDistance, warnDistance }
}

// 한 칸의 입력 문자열을 숫자로 바꾼다. 소수나 음수, 문자는 숫자로 읽지 않고 안내한다.
function parseDistance(text: string, label: string): number {
  const trimmed = text.trim()
  if (!trimmed) throw new Error(`${label} 임계값을 입력해 주세요.`)
  if (!/^\d+$/.test(trimmed)) throw new Error(`${label} 임계값은 0 이상의 정수로 입력해 주세요.`)
  return Number(trimmed)
}

// 화면 입력(문자열 두 개)을 검사해 서버로 보낼 숫자 두 개로 만든다.
// 틀린 입력이면 사용자에게 그대로 보여 줄 문구의 Error를 던진다.
export function parseSimilarityDraft(
  draft: SimilarityPolicyDraft,
  limits: SimilarityPolicyLimits,
): UpdateSimilarityPolicyInput {
  const blockDistance = parseDistance(draft.blockDistance, '차단')
  const warnDistance = parseDistance(draft.warnDistance, '경고')
  return validateSimilarityRange({ blockDistance, warnDistance }, limits)
}