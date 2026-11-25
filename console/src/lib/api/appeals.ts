import { apiRequest } from './client'

// 서버 AppealView(Task 5)와 같은 이름. 공개 응답이라 URL·호스트·장비 정보는 없다.
export type AppealStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export type AppealVerdict = {
  kind: string
  threat: string
  summary: string
}

export type SubmittedAppeal = {
  status: AppealStatus
  reason: string
  createdAt: string
  decidedAt: string | null
  decisionNote: string | null
}

export type AppealView = {
  blockedAt: string | null
  filename: string | null
  verdict: AppealVerdict | null
  appeal: SubmittedAppeal | null
}

function appealPath(token: string) {
  return '/api/public/appeals/' + encodeURIComponent(token)
}

// 로그인 없는 공개 경로. 토큰만으로 접근하므로 관리자 세션을 붙이지 않는다.
export function getAppeal(token: string, signal?: AbortSignal) {
  return apiRequest<AppealView>(appealPath(token), { auth: false, signal })
}

export function submitAppeal(token: string, reason: string) {
  return apiRequest<AppealView>(appealPath(token), {
    method: 'POST',
    auth: false,
    body: JSON.stringify({ reason }),
  })
}
