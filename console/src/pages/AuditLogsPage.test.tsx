import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { ApiError } from '../lib/api/client'
import type { AuditLogItem } from '../lib/api/auditLogs'
import { auditErrorMessage, formatAuditDetail, validateAuditFilters } from '../lib/audit'
import { AuditLogRow } from './AuditLogsPage'

const item: AuditLogItem = {
  logId: 1, action: 'ADD_WHITELIST', dangerous: true,
  actor: { adminId: 'qa-admin', username: 'qa', role: 'ANALYST' },
  targetType: 'WHITELIST', targetId: 'SHA256:' + 'a'.repeat(64),
  detail: { after: { reason: 'QA 사유' } }, ipAddress: '203.0.113.10', createdAt: '2026-10-25T00:00:00Z',
}

function render(row: AuditLogItem, expanded = false) {
  return renderToStaticMarkup(<table><tbody>
    <AuditLogRow item={row} expanded={expanded} onToggle={() => {}} />
  </tbody></table>)
}

describe('validateAuditFilters', () => {
  it('행위자 ID는 UUID여야 하고 기간은 시작이 끝보다 앞서야 한다', () => {
    expect(validateAuditFilters('', '', '')).toBe('')
    expect(validateAuditFilters('00000000-0000-4000-8000-000000000001', '', '')).toBe('')
    expect(validateAuditFilters('not-uuid', '', '')).not.toBe('')
    expect(validateAuditFilters('', '2026-10-22T00:00', '2026-10-21T00:00')).not.toBe('')
    expect(validateAuditFilters('', '2026-10-21T00:00', '2026-10-22T00:00')).toBe('')
  })
})

describe('formatAuditDetail', () => {
  it('객체는 들여쓴 JSON으로, 문자열은 그대로, 없으면 빈 값으로 보여준다', () => {
    expect(formatAuditDetail({ a: 1 })).toBe('{\n  "a": 1\n}')
    expect(formatAuditDetail('not-json')).toBe('not-json')
    expect(formatAuditDetail(null)).toBe('')
  })
})

describe('auditErrorMessage', () => {
  it('권한 없음과 입력 오류, 일반 실패를 구분한다', () => {
    expect(auditErrorMessage(new ApiError(403, {}))).toContain('ADMIN')
    expect(auditErrorMessage(new ApiError(400, { message: 'action is not a known value: X' })))
      .toContain('action is not a known value: X')
    expect(auditErrorMessage(new Error('network'))).toContain('불러오지 못했습니다')
  })
})

describe('AuditLogRow', () => {
  it('조작 이름·위험 표시·행위자·대상을 보여준다', () => {
    const html = render(item)
    expect(html).toContain('화이트리스트 등록')
    expect(html).toContain('ADD_WHITELIST')
    expect(html).toContain('위험')
    expect(html).toContain('qa')
    expect(html).toContain('ANALYST')
    expect(html).toContain(item.targetId)
    expect(html).toContain('203.0.113.10')
    expect(html).not.toContain('QA 사유')
  })

  it('위험하지 않은 조작에는 위험 표시가 없다', () => {
    expect(render({ ...item, action: 'ADD_BLACKLIST', dangerous: false })).not.toContain('위험')
  })

  it('행위자가 없으면 확인되지 않음으로 표시한다', () => {
    expect(render({ ...item, action: 'LOGIN_FAILED', dangerous: false, actor: null })).toContain('확인되지 않음')
  })

  it('펼치면 detail을 HTML이 아닌 글자로 보여준다', () => {
    const html = render({ ...item, detail: { after: { original_filename: '<img src=x onerror=alert(1)>' } } }, true)
    expect(html).toContain('&lt;img src=x onerror=alert(1)&gt;')
    expect(html).not.toContain('<img')
  })

  it('detail이 없으면 펼치기 버튼이 없다', () => {
    expect(render({ ...item, detail: null })).not.toContain('상세')
  })
})
