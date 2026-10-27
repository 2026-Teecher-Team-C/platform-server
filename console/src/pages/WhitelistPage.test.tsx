import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { ApiError } from '../lib/api/client'
import type { WhitelistItem } from '../lib/api/hashlists'
import { isWhitelistExpired, toDateTimeLocal, validateWhitelistForm, whitelistErrorMessage } from '../lib/whitelist'
import { WhitelistRow } from './WhitelistPage'

const item: WhitelistItem = {
  hashType: 'SHA256', hashValue: 'a'.repeat(64), reason: 'QA 사유', origin: 'PREREGISTERED',
  originQuarantineId: null, addedBy: { adminId: 'qa-admin', username: 'qa' }, isActive: true,
  expiresAt: null, createdAt: '2026-10-25T00:00:00Z',
}

const noop = () => {}
const actions = {
  onEdit: noop, onCancel: noop, onSave: noop, onReasonChange: noop,
  onRemove: noop, onConfirmRemove: noop, onCopy: noop,
}

function render(row: WhitelistItem, props: Partial<Parameters<typeof WhitelistRow>[0]> = {}) {
  return renderToStaticMarkup(<MemoryRouter><table><tbody>
    <WhitelistRow item={row} mode="view" draftReason="" isBusy={false} copied={false} expired={false} actions={actions} {...props} />
  </tbody></table></MemoryRouter>)
}

describe('validateWhitelistForm', () => {
  it('64자리 hex SHA-256과 사유가 있어야 통과한다', () => {
    expect(validateWhitelistForm('a'.repeat(64), '사유')).toBe('')
    expect(validateWhitelistForm('A'.repeat(64), '사유')).toBe('')
    expect(validateWhitelistForm('a'.repeat(63), '사유')).not.toBe('')
    expect(validateWhitelistForm('g'.repeat(64), '사유')).not.toBe('')
    expect(validateWhitelistForm('a'.repeat(64), '   ')).not.toBe('')
  })
})

describe('isWhitelistExpired', () => {
  const now = new Date('2026-10-27T00:00:00Z').getTime()

  it('만료 시각이 지났을 때만 만료로 본다', () => {
    expect(isWhitelistExpired({ expiresAt: null }, now)).toBe(false)
    expect(isWhitelistExpired({ expiresAt: '2026-10-28T00:00:00Z' }, now)).toBe(false)
    expect(isWhitelistExpired({ expiresAt: '2026-10-27T00:00:00Z' }, now)).toBe(true)
    expect(isWhitelistExpired({ expiresAt: '2026-10-26T00:00:00Z' }, now)).toBe(true)
  })
})

describe('toDateTimeLocal', () => {
  it('datetime-local 입력이 받는 로컬 시각 형식으로 바꾼다', () => {
    expect(toDateTimeLocal(new Date(2026, 0, 5, 9, 7, 30))).toBe('2026-01-05T09:07')
  })
})

describe('whitelistErrorMessage', () => {
  it('충돌·없는 항목·입력 오류를 구분해 안내한다', () => {
    expect(whitelistErrorMessage(new ApiError(409, {}))).toContain('이미')
    expect(whitelistErrorMessage(new ApiError(404, {}))).toContain('찾을 수 없습니다')
    expect(whitelistErrorMessage(new ApiError(400, { message: 'expiresAt must be in the future' })))
      .toContain('expiresAt must be in the future')
    expect(whitelistErrorMessage(new Error('network'))).toContain('다시 시도')
  })
})

describe('WhitelistRow', () => {
  it('사전 등록 항목은 격리 링크 없이 수정·해제 버튼을 보여준다', () => {
    const html = render(item)
    expect(html).toContain(item.hashValue)
    expect(html).toContain('사전 등록')
    expect(html).toContain('무기한')
    expect(html).toContain('사유 수정')
    expect(html).toContain('해제')
    expect(html).not.toContain('/quarantine/')
  })

  it('복원으로 들어온 항목은 원본 격리 상세로 연결한다', () => {
    const html = render({ ...item, origin: 'RESTORED', originQuarantineId: 'qa-quarantine' })
    expect(html).toContain('오탐 복원')
    expect(html).toContain('/quarantine/qa-quarantine')
  })

  it('비활성 항목은 수정·해제할 수 없다', () => {
    const html = render({ ...item, isActive: false })
    expect(html).toContain('해제됨')
    expect(html).not.toContain('사유 수정')
  })

  it('사유 수정 중인 행은 그 자리에 입력란과 저장·취소를 보여준다', () => {
    const html = render(item, { mode: 'editing', draftReason: '고친 사유' })
    expect(html).toContain('value="고친 사유"')
    expect(html).toContain('저장')
    expect(html).toContain('취소')
    expect(html).not.toContain('사유 수정</button>')
  })

  it('해제는 같은 행에서 한 번 더 확인받는다', () => {
    const html = render(item, { mode: 'confirming' })
    expect(html).toContain('해제 확인')
    expect(html).toContain('취소')
  })

  it('해시를 복사할 수 있고 복사 후에는 알려준다', () => {
    expect(render(item)).toContain('aria-label="SHA-256 복사"')
    expect(render(item, { copied: true })).toContain('복사됨')
  })

  it('만료된 항목은 만료 시각 옆에 만료됨을 표시한다', () => {
    const expiredItem = { ...item, expiresAt: '2026-10-01T00:00:00Z' }
    expect(render(expiredItem, { expired: true })).toContain('만료됨')
    expect(render(expiredItem)).not.toContain('만료됨')
  })
})
