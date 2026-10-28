import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { expect, it } from 'vitest'
import { BlacklistPage, BlacklistResults } from './BlacklistPage'
import { mockBlacklist } from '../mocks/data/hashlists'
import { BlacklistActionPanel } from '../components/blacklist/BlacklistActionPanel'

it('블랙리스트 등록 폼, 검색 조건과 첫 페이지 로딩 상태를 보여준다', () => {
  const html = renderToStaticMarkup(createElement(BlacklistPage))
  expect(html).toContain('SHA-256 앞부분')
  expect(html).toContain('심각도')
  expect(html).toContain('출처')
  expect(html).toContain('활성 여부')
  expect(html).toContain('불러오는 중')
  expect(html).toContain('등록 사유')
  expect(html).toContain('64자리 SHA-256')
})

it('등록용/검색용 해시를 한 번에 지우는 버튼을 제공하며 폼 제출과 구분한다', () => {
  const html = renderToStaticMarkup(createElement(BlacklistPage))
  expect(html).toContain('aria-label="등록 SHA-256 지우기"')
  expect(html).toContain('aria-label="검색 SHA-256 지우기"')
  expect(html.match(/type="button"[^>]*aria-label="(?:등록|검색) SHA-256 지우기"/g)).toHaveLength(2)
})

it('등록자와 해제 시각이 없는 항목도 전체 해시와 함께 안전하게 표시한다', () => {
  const html = renderToStaticMarkup(createElement(BlacklistResults, { items: [mockBlacklist[1]], isLoading: false, error: false, onRetry: () => {} }))
  expect(html).toContain(mockBlacklist[1].hashValue)
  expect(html).toContain('CRITICAL')
  expect(html).toContain('가져오기')
  expect(html.match(/—/g)).toHaveLength(2)
})

it('비활성 항목의 해제 시각과 이름 없는 등록자의 ID를 표시한다', () => {
  const html = renderToStaticMarkup(createElement(BlacklistResults, {
    items: [{ ...mockBlacklist[0], isActive: false, addedBy: { adminId: 'qa-admin-id', username: null }, deactivatedAt: '2026-10-12T00:00:00Z' }],
    isLoading: false, error: false, onRetry: () => {},
  }))
  expect(html).toContain('비활성')
  expect(html).toContain('qa-admin-id')
  expect(html).toContain('2026')
  expect(html).not.toContain('—')
})

it('빈 결과와 재시도 가능한 오류를 구분하며 로딩 중에는 이전 목록을 숨긴다', () => {
  const props = { items: [], isLoading: false, error: false, onRetry: () => {} }
  expect(renderToStaticMarkup(createElement(BlacklistResults, props))).toContain('조건에 맞는')
  expect(renderToStaticMarkup(createElement(BlacklistResults, { ...props, error: true }))).toContain('다시 시도')
  const loading = renderToStaticMarkup(createElement(BlacklistResults, { ...props, items: mockBlacklist, isLoading: true }))
  expect(loading).toContain('불러오는 중')
  expect(loading).not.toContain(mockBlacklist[0].hashValue)
})

it('현재 상태에 맞는 반대 상태 전환 버튼을 표시하고 처리 중에는 막는다', () => {
  const props = { items: [mockBlacklist[0]], isLoading: false, error: false, onRetry: () => {}, onSelect: () => {}, disabled: true }
  const active = renderToStaticMarkup(createElement(BlacklistResults, props))
  expect(active).toContain('사유 수정')
  expect(active).toContain('>비활성화</button>')
  expect(active).not.toContain('>활성화</button>')
  expect(active).toContain('disabled')
  const inactive = renderToStaticMarkup(createElement(BlacklistResults, { ...props, items: [{ ...mockBlacklist[0], isActive: false }] }))
  expect(inactive).toContain('>활성화</button>')
  expect(inactive).not.toContain('>비활성화</button>')
  expect(inactive).toContain('disabled')
  expect(inactive).not.toContain('사유 수정')
})

it('해제 전 대상 해시, 재검사 경고, 확인과 취소를 표시한다', () => {
  const html = renderToStaticMarkup(createElement(BlacklistActionPanel, {
    selection: { item: mockBlacklist[0], mode: 'remove' }, reason: '', busy: true, error: '',
    onReasonChange: () => {}, onConfirm: () => {}, onCancel: () => {},
  }))
  expect(html).toContain(mockBlacklist[0].hashValue)
  expect(html).toContain('자동 허용되지는 않습니다')
  expect(html).toContain('비활성화 확인')
  expect(html).toContain('취소')
  expect(html).toContain('disabled')
})

it('사유 수정 폼은 서버 오류를 안내하고 사유를 HTML로 실행하지 않는다', () => {
  const html = renderToStaticMarkup(createElement(BlacklistActionPanel, {
    selection: { item: mockBlacklist[0], mode: 'edit' }, reason: '<script>alert(1)</script>', busy: false, error: '목록을 새로고침해 주세요.',
    onReasonChange: () => {}, onConfirm: () => {}, onCancel: () => {},
  }))
  expect(html).toContain('수정할 사유')
  expect(html).toContain('role="alert"')
  expect(html).toContain('&lt;script&gt;')
  expect(html).not.toContain('<script>')
})

it('활성화 전 대상, 사유와 재등록 API의 심각도/출처 변경을 안내한다', () => {
  const html = renderToStaticMarkup(createElement(BlacklistActionPanel, {
    selection: { item: { ...mockBlacklist[0], isActive: false }, mode: 'activate' }, reason: '기존 사유', busy: true, error: '충돌 안내',
    onReasonChange: () => {}, onConfirm: () => {}, onCancel: () => {},
  }))
  expect(html).toContain(mockBlacklist[0].hashValue)
  expect(html).toContain('활성화 사유')
  expect(html).toContain('기존 사유')
  expect(html).toContain('HIGH')
  expect(html).toContain('수동 등록')
  expect(html).toContain('활성화 확인')
  expect(html).toContain('role="alert"')
  expect(html).toContain('disabled')
})
