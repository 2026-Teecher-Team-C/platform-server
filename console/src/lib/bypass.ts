export type BypassInput = { host: string; category: string; reason: string }

// BypassHost.of와 같은 순서로 검사한다. URL 파서로 보정하거나 와일드카드를 허용하면 검사 예외 범위가 넓어진다.
export function validateBypassInput(input: BypassInput): BypassInput {
  // Java String.trim은 ASCII 제어 문자까지 제거한다. 마지막 점은 정확히 하나만 지운다.
  // eslint-disable-next-line no-control-regex -- 서버 trim과 일치시키기 위한 의도적인 제어 문자 범위다.
  const host = input.host.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '').toLowerCase().replace(/\.$/, '')
  if (!host) throw new Error('도메인을 입력해 주세요.')
  if (host.includes('*')) throw new Error('와일드카드(*)는 사용할 수 없습니다.')
  if (/[/?#@:]/.test(host)) throw new Error('스킴, 경로, 포트, 계정 정보 없이 도메인만 입력해 주세요.')
  const labels = host.split('.')
  if (host.length > 253 || labels.length < 2 || labels.some((label) => label.length > 63 || !/^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/.test(label)) || !/[a-z]/.test(labels[labels.length - 1])) {
    throw new Error('점으로 구분된 영문 도메인을 입력해 주세요. 전체 253자, 각 부분 63자 이하이며 IP 주소는 사용할 수 없습니다.')
  }
  if (!['PINNED', 'SECURITY_UPDATE'].includes(input.category)) throw new Error('카테고리를 선택해 주세요.')
  // 서버는 사유의 빈 값만 검사하고 원문을 저장한다. 임의 길이 제한이나 trim 저장을 추가하지 않는다.
  // Java isBlank는 NBSP 등을 공백으로 보지 않는다. JS trim과 차이를 맞추되 사유 원문은 그대로 둔다.
  // eslint-disable-next-line no-control-regex -- Java Character.isWhitespace와 같은 제어 문자 범위다.
  if (/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/.test(input.reason)) throw new Error('등록 사유를 입력해 주세요.')
  return { ...input, host }
}
