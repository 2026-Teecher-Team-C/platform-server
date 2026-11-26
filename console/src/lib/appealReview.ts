import { ApiError } from './api/client'
import type { AppealStatus } from './api/appeals'
import type { AppealEvidenceKind } from './api/adminAppeals'

export const APPEAL_STATUS_LABEL: Record<AppealStatus, string> = {
  PENDING: '검토 대기',
  APPROVED: '승인',
  REJECTED: '거절',
}

// 콘솔 기존 배지 색을 쓴다: 대기 회색, 승인 초록, 거절 빨강.
export const APPEAL_STATUS_BADGE: Record<AppealStatus, string> = {
  PENDING: 'events-badge events-badge--pending',
  APPROVED: 'events-badge events-badge--released',
  REJECTED: 'events-badge events-badge--blocked',
}

type Evidence = { summary: string; defaultThreat: string; label: string }

// summary·defaultThreat는 서버 AppealView.verdictOf(직원 소명 페이지·에이전트 차단 페이지)와 같은 문구다.
const EVIDENCE: Record<Exclude<AppealEvidenceKind, 'NONE'>, Evidence> = {
  BLACKLIST: { summary: '보안 위협 목록에 등록된 파일과 똑같은 파일입니다.', defaultThreat: '알려진 악성 파일', label: '목록 일치' },
  SIMILARITY: { summary: '알려진 악성코드와 매우 비슷한 파일입니다. 일부만 바꾼 변종일 수 있습니다.', defaultThreat: '알려진 악성코드 변종', label: '유사도' },
  YARA: { summary: '악성코드 탐지 규칙에 걸렸습니다.', defaultThreat: '악성코드 탐지 규칙', label: 'YARA' },
  CACHE: { summary: '이전에 악성으로 판정된 파일입니다.', defaultThreat: '이전 검사 결과', label: '이전 판정' },
}

// V22 이전 소명이거나 근거 없는 차단이면 null. 화면은 "판정 근거 없음"으로 보여 준다.
export function appealEvidence(kind: AppealEvidenceKind | null, threat: string | null) {
  if (!kind || kind === 'NONE') return null
  const evidence = EVIDENCE[kind]
  if (!evidence) return null
  return { summary: evidence.summary, label: evidence.label, threat: threat || evidence.defaultThreat }
}

// 목록의 위협 열. 예: "Mirai (유사도)", "EICAR (목록 일치)"
export function appealThreatLabel(kind: AppealEvidenceKind | null, threat: string | null) {
  const evidence = appealEvidence(kind, threat)
  return evidence ? `${evidence.threat} (${evidence.label})` : '—'
}

// 관제 화면처럼 "11-26 10:12"로 줄인다(브라우저 시간대). 전체 시각은 title로 둔다.
export function formatAppealTime(iso: string | null) {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function formatAppealFullTime(iso: string | null) {
  return iso ? new Date(iso).toLocaleString('ko-KR') : undefined
}

export const DECISION_NOTE_MAX = 1000

// 서버와 같이 앞뒤 공백을 뺀 뒤 코드 포인트로 센다.
export function decisionNoteLength(value: string) {
  return [...value.trim()].length
}

export type DecisionError = { message: string; needsReload: boolean }

export function appealDecisionError(cause: unknown, mode: 'approve' | 'reject' = 'reject'): DecisionError {
  if (cause instanceof ApiError) {
    if (cause.status === 409) return { message: '다른 담당자가 먼저 처리했습니다. 상태를 새로고침해 주세요.', needsReload: true }
    if (cause.status === 400) {
      // 승인 의견은 선택이라 "1자 이상" 조건이 없다.
      const message = mode === 'approve' ? '의견은 1000자 이하로 입력해 주세요.' : '의견을 1자 이상 1000자 이하로 입력해 주세요.'
      return { message, needsReload: false }
    }
    if (cause.status === 404) return { message: '소명 요청을 찾을 수 없습니다. 목록을 확인해 주세요.', needsReload: true }
    if (cause.status === 401) return { message: '로그인이 만료되었습니다. 다시 로그인해 주세요.', needsReload: false }
    if (cause.status === 403) return { message: '소명을 승인·거절할 권한이 없습니다.', needsReload: false }
  }
  // 처리된 뒤 응답만 끊겼을 수 있다. 상태를 확인하기 전에는 같은 결정을 다시 보내지 않는다.
  return { message: '처리 결과를 확인하지 못했습니다. 서버에서 처리됐을 수 있으니 상태를 새로고침해 주세요.', needsReload: true }
}
