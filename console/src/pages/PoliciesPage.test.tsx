import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { BypassDomainResults } from './PoliciesPage'
import type { BypassDomainItem } from '../lib/api/policies'

const item: BypassDomainItem = {
  bypassId: 'qa-bypass', host: '*.qa.example.test', category: 'PINNED',
  reason: 'QA 피닝 예외', isActive: false, createdAt: '2026-10-25T00:00:00Z',
}

describe('BypassDomainResults', () => {
  it('로딩 중에는 이전 결과를 감추고 오류에는 재시도 버튼을 제공한다', () => {
    const loading = renderToStaticMarkup(<BypassDomainResults items={[item]} isLoading={true} error={false} onRetry={() => {}} />)
    expect(loading).toContain('role="status"')
    expect(loading).not.toContain(item.host)
    const failed = renderToStaticMarkup(<BypassDomainResults items={[]} isLoading={false} error={true} onRetry={() => {}} />)
    expect(failed).toContain('다시 시도')
  })

  it('빈 목록을 조회 실패와 구분한다', () => {
    const html = renderToStaticMarkup(<BypassDomainResults items={[]} isLoading={false} error={false} onRetry={() => {}} />)
    expect(html).toContain('조건에 맞는 바이패스 도메인이 없습니다')
    expect(html).not.toContain('다시 시도')
  })

  it('조회 결과의 도메인·분류·적용 여부를 읽기 전용으로 보여준다', () => {
    const html = renderToStaticMarkup(<BypassDomainResults items={[item]} isLoading={false} error={false} onRetry={() => {}} />)
    expect(html).toContain('*.qa.example.test')
    expect(html).toContain('PINNED')
    expect(html).toContain('미적용')
    expect(html).toContain('QA 피닝 예외')
    expect(html).not.toContain('href=')
    expect(html).not.toContain('수정')
  })
})
