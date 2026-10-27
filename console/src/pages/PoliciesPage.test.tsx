import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { BypassDomainResults, PoliciesPage } from './PoliciesPage'
import type { BypassDomainItem } from '../lib/api/policies'

const item: BypassDomainItem = {
  bypassId: 'qa-bypass', host: '*.qa.example.test', category: 'PINNED',
  reason: 'QA 피닝 예외', isActive: false, createdAt: '2026-10-25T00:00:00Z',
}

describe('BypassDomainResults', () => {
  it('검색 필터는 유지하고 불필요한 검색 방식 안내는 표시하지 않는다', () => {
    const html = renderToStaticMarkup(<PoliciesPage />)
    expect(html).toContain('도메인 검색어')
    expect(html).not.toContain('검색어는 도메인 패턴의 일부와 일치하는 항목을 찾습니다.')
    expect(html).not.toContain('대소문자는 구분하지 않습니다.')
  })

  it('첫 조회에는 로딩을 표시하고 오류에는 재시도 버튼을 제공한다', () => {
    const loading = renderToStaticMarkup(<BypassDomainResults items={[]} isLoading={true} error={false} onRetry={() => {}} />)
    expect(loading).toContain('role="status"')
    expect(loading).not.toContain(item.host)
    const failed = renderToStaticMarkup(<BypassDomainResults items={[]} isLoading={false} error={true} onRetry={() => {}} />)
    expect(failed).toContain('다시 시도')
  })

  it('재조회 중에는 기존 목록을 로딩 문구로 교체하지 않는다', () => {
    const html = renderToStaticMarkup(<BypassDomainResults items={[item]} isLoading={true} error={false} onRetry={() => {}} />)
    expect(html).toContain(item.host)
    expect(html).not.toContain('불러오는 중입니다')
  })

  it('재조회 실패 시 기존 목록과 오류 안내를 함께 표시한다', () => {
    const html = renderToStaticMarkup(<BypassDomainResults items={[item]} isLoading={false} error={true} onRetry={() => {}} />)
    expect(html).toContain(item.host)
    expect(html).toContain('다시 시도')
    expect(html).toContain('이전 조회 결과')
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
