// console/src/lib/auth/roles.ts
//
// "역할별로 볼 수 있는 메뉴"를 한곳에 모아 둔 파일이다.
// 사이드바 필터, 라우트 가드, 테스트가 모두 이 파일 하나만 보게 한다.
// 기준은 팀 기능 명세표(역할별 API 권한)이며, 권한을 바꿀 일이 생기면
// 아래 MENU_ACCESS만 고치면 된다.

// 역할 타입은 새로 만들지 않고 API 타입(AdminRole)을 그대로 쓴다.
// 역할 정의가 두 곳이 되면 서로 어긋나기 때문이다.
import type { AdminRole } from '../api/auth'

// ── 메뉴 ─────────────────────────────────────────────
// 사이드바 메뉴 10개의 고유한 이름표(key)다. 화면에 보이는 한글 이름이나
// 주소와는 따로 둔다. 주소나 이름이 바뀌어도 권한 표는 그대로 쓰기 위해서다.
export const MENU_KEYS = [
  'dashboard',        // 대시보드
  'downloadEvents',   // 다운로드 이벤트
  'quarantine',       // 격리 파일
  'agents',           // PC 현황
  'enrollmentTokens', // 등록 토큰 발급
  'whitelist',        // 화이트리스트
  'blacklist',        // 블랙리스트
  'policy',           // 정책 (우회 도메인)
  'yaraRules',        // YARA 룰셋
  'auditLog',         // 감사 로그
] as const
export type MenuKey = (typeof MENU_KEYS)[number]

// ── 권한 표 ──────────────────────────────────────────
// 각 메뉴를 "볼 수 있는" 역할이다. 여기에 없는 역할(예: VIEWER, USER)은
// 어떤 메뉴도 볼 수 없다.
//
// Record<MenuKey, ...> 라서 MENU_KEYS에 메뉴를 추가하고 여기에 줄을 안 넣으면
// 컴파일 에러가 난다. 새 메뉴의 권한을 빼먹는 실수를 막아 준다.
const MENU_ACCESS: Record<MenuKey, readonly AdminRole[]> = {
  dashboard: ['ADMIN', 'ANALYST'],
  downloadEvents: ['ADMIN', 'ANALYST'],
  quarantine: ['ADMIN', 'ANALYST'],   // ANALYST는 조회만 (복원은 ADMIN)
  agents: ['ADMIN', 'ANALYST'],
  enrollmentTokens: ['ADMIN'],
  whitelist: ['ADMIN'],               // 화이트리스트는 전부 ADMIN만
  blacklist: ['ADMIN', 'ANALYST'],    // ANALYST는 조회만
  policy: ['ADMIN', 'ANALYST'],       // ANALYST는 조회만
  yaraRules: ['ADMIN', 'ANALYST'],    // ANALYST는 조회만
  auditLog: ['ADMIN'],
}

// ── 조회 함수 ────────────────────────────────────────
// 이 역할이 이 메뉴에 접근할 수 있는지 확인한다.
// 역할이 없는 경우(로그인 전, 알 수 없는 값)는 항상 불허한다.
// "모르면 막는다"가 기본이라 안전한 쪽으로 실패한다.
export function canAccess(role: AdminRole | null | undefined, menu: MenuKey): boolean {
  if (!role) return false
  return MENU_ACCESS[menu].includes(role)
}

// 이 역할에게 보여 줄 메뉴 key 목록. 사이드바가 이 목록으로 메뉴를 거른다.
export function allowedMenus(role: AdminRole | null | undefined): MenuKey[] {
  return MENU_KEYS.filter((menu) => canAccess(role, menu))
}

// 등록·수정·삭제·복원 같은 "변경" 버튼을 보여 줄 수 있는지.
// 명세표에서 모든 변경 API가 ADMIN 전용이라 역할 하나로 판단한다.
// ANALYST는 메뉴에는 들어오지만 변경 버튼은 못 쓴다.
export function canManage(role: AdminRole | null | undefined): boolean {
  return role === 'ADMIN'
}