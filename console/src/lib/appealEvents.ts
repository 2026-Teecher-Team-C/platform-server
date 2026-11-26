// 소명 결정(승인·거절) 직후 사이드바 대기 건수가 30초를 기다리지 않고 바로 갱신되게 하는 알림.
export const APPEALS_CHANGED_EVENT = 'console:appeals-changed'

export function notifyAppealsChanged() {
  window.dispatchEvent(new Event(APPEALS_CHANGED_EVENT))
}
