import { describe, expect, it } from 'vitest'
import { NAV_ITEMS, findNavItemByPath } from '../../components/layout/navigation'
import { MENU_KEYS, allowedMenus, canAccess, canManage } from './roles'

describe('역할별 메뉴 권한', () => {
  it('ADMIN은 모든 메뉴를 볼 수 있다', () => {
    expect(allowedMenus('ADMIN')).toEqual([...MENU_KEYS])
  })

  it('ANALYST는 등록 토큰 발급, 화이트리스트, 감사 로그만 볼 수 없다', () => {
    const menus = allowedMenus('ANALYST')

    expect(menus).toHaveLength(7)
    expect(menus).not.toContain('enrollmentTokens')
    expect(menus).not.toContain('whitelist')
    expect(menus).not.toContain('auditLog')
    // 조회 권한이 있는 메뉴는 들어올 수 있다.
    expect(canAccess('ANALYST', 'dashboard')).toBe(true)
    expect(canAccess('ANALYST', 'quarantine')).toBe(true)
    expect(canAccess('ANALYST', 'blacklist')).toBe(true)
  })

  it('역할이 없거나 권한 표에 없는 역할은 어떤 메뉴도 볼 수 없다', () => {
    expect(allowedMenus(undefined)).toEqual([])
    expect(allowedMenus(null)).toEqual([])
    expect(allowedMenus('VIEWER')).toEqual([])
  })

  it('변경 권한은 ADMIN만 가진다', () => {
    expect(canManage('ADMIN')).toBe(true)
    expect(canManage('ANALYST')).toBe(false)
    expect(canManage('VIEWER')).toBe(false)
    expect(canManage(undefined)).toBe(false)
  })
})

describe('사이드바 메뉴 목록과 주소', () => {
  it('권한 표의 모든 메뉴가 사이드바 목록에 한 번씩 있다', () => {
    const keys = NAV_ITEMS.map((item) => item.key)

    expect([...keys].sort()).toEqual([...MENU_KEYS].sort())
  })

  it('상세 화면 주소는 목록과 같은 메뉴로 본다', () => {
    expect(findNavItemByPath('/quarantine/123')?.key).toBe('quarantine')
    expect(findNavItemByPath('/events/abc')?.key).toBe('downloadEvents')
  })

  it('등록 토큰 발급 주소는 PC 현황이 아니라 등록 토큰 발급으로 본다', () => {
    expect(findNavItemByPath('/agents')?.key).toBe('agents')
    expect(findNavItemByPath('/agents/enrollment-token')?.key).toBe('enrollmentTokens')
  })

  it('주소의 대소문자가 달라도 같은 메뉴로 본다', () => {
    expect(findNavItemByPath('/Whitelist')?.key).toBe('whitelist')
    expect(findNavItemByPath('/AUDIT-LOGS')?.key).toBe('auditLog')
  })

  it('메뉴에 없는 주소는 찾지 않는다', () => {
    expect(findNavItemByPath('/login')).toBeUndefined()
    expect(findNavItemByPath('/unknown')).toBeUndefined()
    // 앞부분만 같은 주소는 다른 주소다.
    expect(findNavItemByPath('/dashboardx')).toBeUndefined()
  })
})