/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

// 제목 아래의 단순 소개 문구만 제거한다. 권한, 오류, 입력 방법과 토큰 보관 안내는 대상이 아니다.
const descriptions = [
  ['AgentsPage.tsx', '등록된 에이전트의 상태, 버전, 마지막 하트비트를 확인합니다.'],
  ['EnrollmentTokenPage.tsx', '새 PC에 Agent를 등록할 때 사용할 1회용 토큰을 발급합니다.'],
  ['EventsPage.tsx', '다운로드 이벤트의 판정 결과를 필터링해 확인합니다.'],
  ['EventDetailPage.tsx', '다운로드 판정과 PC 정보를 확인합니다.'],
  ['QuarantineDetailPage.tsx', '격리 파일의 정보와 연관 다운로드 이벤트를 확인합니다.'],
  ['BlacklistPage.tsx', '차단 대상 SHA-256을 등록하고 관리합니다.'],
  ['PoliciesPage.tsx', '바이패스 도메인의 카테고리와 적용 여부를 조회합니다.'],
  ['AuditLogsPage.tsx', '관리자가 한 등록·해제·복원·발급·로그인 기록입니다. 검사를 약하게 만드는 조작은 위험으로 표시합니다.'],
]

describe('관리 화면 상단 소개 문구', () => {
  it.each(descriptions)('%s의 제목 아래 소개 문구를 제거한다', (file, description) => {
    const source = readFileSync(new URL(file, import.meta.url), 'utf8')
    expect(source.includes(description)).toBe(false)
  })
})
