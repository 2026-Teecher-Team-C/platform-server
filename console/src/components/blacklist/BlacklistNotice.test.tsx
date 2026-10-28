import { renderToStaticMarkup } from 'react-dom/server'
import { expect, it } from 'vitest'
import { BlacklistNotice } from './BlacklistNotice'

it('성공 알림을 닫을 수 있는 상태 메시지로 표시한다', () => {
  const html = renderToStaticMarkup(<BlacklistNotice message="블랙리스트에서 해제했습니다." onDismiss={() => {}} />)
  expect(html).toContain('role="status"')
  expect(html).toContain('aria-live="polite"')
  expect(html).toContain('블랙리스트에서 해제했습니다.')
  expect(html).toContain('aria-label="알림 닫기"')
  expect(html).toContain('type="button"')
})

it('알림이 없을 때는 빈 영역을 만들지 않는다', () => {
  expect(renderToStaticMarkup(<BlacklistNotice message="" onDismiss={() => {}} />)).toBe('')
})
