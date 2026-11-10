// console/src/components/layout/navigation.ts
//
// 사이드바 메뉴 목록. 사이드바(메뉴 그리기)와 라우트 가드(주소 → 메뉴 찾기)가
// 같은 목록을 쓰도록 한곳에 둔다.
//
// key  : lib/auth/roles.ts의 권한 표와 연결되는 이름표
// to   : 이동할 주소 (기존 Sidebar.tsx의 navigation 배열과 같은 값)
// label: 화면에 보이는 한글 이름
import type { MenuKey } from '../../lib/auth/roles'

// 사이드바 그룹. 관리자 업무 순서(탐지 → 대응 → 정책 → 운영 → 관리)로 묶는다.
// 설계 레포 docs/research/2026-11-04-dashboard-redesign.md 3장. 아직 화면이 없는
// 소명 요청(대응)·운영 현황(운영)은 화면이 생길 때 추가한다.
export const NAV_GROUPS = ['탐지', '대응', '정책', '운영', '관리'] as const
export type NavGroup = (typeof NAV_GROUPS)[number]

export type NavItem = {
  key: MenuKey
  to: string
  label: string
  group: NavGroup
}

export const NAV_ITEMS: readonly NavItem[] = [
  { key: 'dashboard', to: '/dashboard', label: '위협 대시보드', group: '탐지' },
  { key: 'downloadEvents', to: '/events', label: '탐지 이벤트', group: '탐지' },
  { key: 'quarantine', to: '/quarantine', label: '격리 파일', group: '대응' },
  { key: 'policy', to: '/policies', label: '검사 정책', group: '정책' },
  { key: 'whitelist', to: '/whitelist', label: '화이트리스트', group: '정책' },
  { key: 'blacklist', to: '/blacklist', label: '블랙리스트', group: '정책' },
  { key: 'yaraRules', to: '/rulesets', label: 'YARA 룰셋', group: '정책' },
  { key: 'agents', to: '/agents', label: 'PC 현황', group: '운영' },
  { key: 'enrollmentTokens', to: '/agents/enrollment-token', label: '등록 토큰 발급', group: '관리' },
  { key: 'auditLog', to: '/audit-logs', label: '감사 로그', group: '관리' },
]

// 주소가 긴 것부터 비교하기 위해 미리 정렬해 둔다.
// '/agents/enrollment-token'은 '/agents'로 시작하므로, 짧은 주소부터 비교하면
// 등록 토큰 화면이 'PC 현황' 메뉴로 잘못 잡힌다.
const NAV_ITEMS_LONGEST_FIRST = [...NAV_ITEMS].sort((a, b) => b.to.length - a.to.length)

// 현재 주소(pathname)가 어느 메뉴에 속하는지 찾는다.
// - '/quarantine'과 '/quarantine/123' 같은 상세 화면 모두 '격리 파일'로 본다.
// - 목록에 없는 주소(로그인 화면 등)는 undefined를 돌려준다.
export function findNavItemByPath(pathname: string): NavItem | undefined {
  // react-router는 주소의 대소문자를 구분하지 않으므로 여기서도 소문자로 맞춰 비교한다.
  // (/Whitelist 같은 주소로 가드를 피해 가는 것을 막기 위함)
  const path = pathname.toLowerCase()
  return NAV_ITEMS_LONGEST_FIRST.find(
    (item) => path === item.to || path.startsWith(item.to + '/'),
  )
}