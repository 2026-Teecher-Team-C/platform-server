import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { QuarantineDetailContent } from './QuarantineDetailPage'
import type { getQuarantineFile } from '../lib/api/quarantine'

type Detail = Awaited<ReturnType<typeof getQuarantineFile>>
const item: Detail = {
  quarantineId: 'qa-quarantine', eventId: 'qa-event', agentId: 'qa-agent', hostname: 'QA-PC',
  sha256: 'a'.repeat(64), originalFilename: null, fileSize: 0, status: 'QUARANTINED',
  quarantinedAt: '2026-10-25T00:00:00Z', restoredAt: null,
}

function render(state: Parameters<typeof QuarantineDetailContent>[0]) {
  return renderToStaticMarkup(<MemoryRouter><QuarantineDetailContent {...state} /></MemoryRouter>)
}

describe('QuarantineDetailContent', () => {
  it('요청 중에는 이전 파일 정보를 노출하지 않는다', () => {
    const html = render({ item, isLoading: true, error: null, onRetry: () => {} })
    expect(html).toContain('role="status"')
    expect(html).not.toContain(item.sha256)
  })

  it('없는 파일과 일반 조회 실패의 안내를 구분한다', () => {
    const missing = render({ item: null, isLoading: false, error: 'not-found', onRetry: () => {} })
    expect(missing).toContain('격리 파일을 찾을 수 없습니다')
    expect(missing).not.toContain('다시 시도')
    const failed = render({ item: null, isLoading: false, error: 'request', onRetry: () => {} })
    expect(failed).toContain('다시 시도')
  })

  it('요청 URL은 실행 가능한 링크가 아닌 텍스트로 표시한다', () => {
    const html = render({ item: { ...item, downloadEvent: { eventId: item.eventId, requestHost: 'qa.example.test', url: 'javascript:alert(1)' } }, isLoading: false, error: null, onRetry: () => {} })
    expect(html).toContain('javascript:alert(1)')
    expect(html).not.toContain('href="javascript:')
  })

  it('nullable 값과 0 bytes를 구분하고 연관 이벤트 링크를 제공한다', () => {
    const html = render({ item, isLoading: false, error: null, onRetry: () => {} })
    expect(html).toContain('파일명 없음')
    expect(html).toContain('0 bytes')
    expect(html).toContain('—')
    expect(html).toContain('/events/qa-event')
    expect(html).toContain(item.sha256)
    expect(html).not.toContain('/agents/qa-agent')
    expect(html).not.toContain('다운로드</')
    expect(html).not.toContain('복원</')
  })
})
