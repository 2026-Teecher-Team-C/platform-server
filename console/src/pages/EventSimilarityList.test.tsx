// @vitest-environment jsdom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { expect, it, vi } from 'vitest'
import { EventsPage } from './EventsPage'
import { DashboardPage } from './DashboardPage'
import { getDownloadEvents } from '../lib/api/events'
vi.mock('../lib/api/events', () => ({ getDownloadEvents: vi.fn() }))

it.each([['목록', EventsPage], ['대시보드', DashboardPage]] as const)('%s에서 TLSH 경고/출처를 표시한다', async (name, Page) => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  // 실제 계약에서 WARN은 RELEASED/ENGINE, 유사도 차단은 BLOCKED/SIMILARITY다.
  const row = { eventId: 'warn', agentId: 'qa', hostname: 'QA-PC', sha256: 'a'.repeat(64), filename: 'warn.txt', mimeType: null, fileSize: 10, decision: 'RELEASED', decisionSource: 'ENGINE', cacheHit: false, holdDurationMs: 1, createdAt: new Date().toISOString(), similarityClassification: 'WARN' } as const
  vi.mocked(getDownloadEvents).mockResolvedValue({ content: [row, { ...row, eventId: 'block', filename: 'block.txt', decision: 'BLOCKED', decisionSource: 'SIMILARITY', similarityClassification: 'BLOCK' }], page: 0, size: 20, totalElements: 2, totalPages: 1 })
  const container = document.createElement('div'); document.body.append(container)
  const root = createRoot(container)
  try {
    await act(async () => root.render(<MemoryRouter><Page /></MemoryRouter>))
    expect(container.textContent).toContain(name === '목록' ? 'RELEASED' : '안전 통과')
    if (name === '목록') expect(container.textContent).toContain('TLSH 경고')
    else expect(container.textContent).toContain('TLSH 유사도')
  } finally { await act(async () => root.unmount()); container.remove() }
})
