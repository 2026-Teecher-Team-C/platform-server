// @vitest-environment jsdom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { expect, it } from 'vitest'
import { SimilarityEvidence } from './SimilarityEvidence'

it('설명은 기본 접힘이며 제목 옆에 경고 아이콘과 상태를 표시한다', async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  const container = document.createElement('div')
  document.body.append(container)
  const root = createRoot(container)
  try {
    await act(async () => root.render(<MemoryRouter><SimilarityEvidence similarity={{ classification: 'WARN', distance: 31,
      matchedSampleSha256: 'ab'.repeat(32), assessedAt: '2026-10-23T00:00:00Z' }} /></MemoryRouter>))
    const details = container.querySelector('details')
    expect(details).not.toBeNull()
    expect(details!.open).toBe(false)
    expect(container.querySelector('h2 .event-similarity-icon--warn')).not.toBeNull()
    expect(container.querySelector('.event-similarity-status')).toBeNull()
    expect(container.querySelector('h2 [role="img"]')?.getAttribute('aria-label')).toBe('유사도 경고')
    // 접힌 설명과 별개로 실제 수치와 기준 샘플은 계속 읽을 수 있어야 한다.
    expect(container.querySelector('dl')?.closest('details')).toBeNull()
    expect(container.querySelector('dl')?.textContent).toContain('31')
    await act(async () => container.querySelector('summary')!.click())
    expect(details!.open).toBe(true)
    expect(details!.textContent).toContain('31~70')
    await act(async () => container.querySelector('summary')!.click())
    expect(details!.open).toBe(false)
  } finally {
    await act(async () => root.unmount())
    container.remove()
  }
})
