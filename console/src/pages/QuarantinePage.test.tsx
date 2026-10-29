import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { QuarantinePage } from './QuarantinePage'

// 제목과 안내 문구만 검증한다. 조회/복원 동작은 기존 API와 화면 테스트가 담당한다.
describe('격리 목록 제목', () => {
  it('저장 위치 안내 없이 Quarantine과 격리 파일 제목을 전용 헤더로 표시한다', () => {
    const html = renderToStaticMarkup(<MemoryRouter><QuarantinePage /></MemoryRouter>)
    expect(html).not.toContain('S3에 격리된 파일의 메타데이터와 상태를 확인합니다.')
    expect(html).not.toContain('원본 저장 위치는 노출되지 않습니다.')
    expect(html).toContain('quarantine-page-heading')
    expect(html).toContain('>Quarantine</p>')
    expect(html).toContain('>격리 파일</h1>')
  })
})
