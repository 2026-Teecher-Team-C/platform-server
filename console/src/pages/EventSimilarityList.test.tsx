// @vitest-environment jsdom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { expect, it, vi } from 'vitest'
import { EventsPage } from './EventsPage'
import { RecentBlocks } from '../components/dashboard/RecentBlocks'
import { getDownloadEvents } from '../lib/api/events'
import type { DownloadEventListItem } from '../lib/api/events'
vi.mock('../lib/api/events', () => ({ getDownloadEvents: vi.fn() }))

const row: DownloadEventListItem = { eventId: 'warn', agentId: 'qa', hostname: 'QA-PC', sha256: 'a'.repeat(64), filename: 'warn.txt', mimeType: null, fileSize: 10, decision: 'RELEASED', decisionSource: 'ENGINE', cacheHit: false, holdDurationMs: 1, createdAt: new Date().toISOString(), similarityClassification: 'WARN', severity: null, topRuleName: null, extraRuleCount: 0, acknowledged: false }
const block: DownloadEventListItem = { ...row, eventId: 'block', filename: 'block.txt', decision: 'BLOCKED', decisionSource: 'SIMILARITY', similarityClassification: 'BLOCK' }

it.each(['목록', '최근 차단'] as const)('%s에서 TLSH 분류/출처와 상세 진입을 보존한다', async (name) => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  vi.mocked(getDownloadEvents).mockResolvedValue({ content: [row, block], page: 0, size: 20, totalElements: 2, totalPages: 1 })
  const container = document.createElement('div'); document.body.append(container)
  const root = createRoot(container)
  try {
    await act(async () => root.render(<MemoryRouter>{name === '목록' ? <EventsPage /> : <RecentBlocks rows={[block]} newIds={new Set()} />}</MemoryRouter>))
    if (name === '목록') {
      expect(container.textContent).toContain('RELEASED')
      expect(container.textContent).toContain('TLSH 경고')
    } else {
      expect(container.textContent).toContain('TLSH 유사도')
    }
    expect(container.querySelector('a[href="/events/block"]')).not.toBeNull()
  } finally { await act(async () => root.unmount()); container.remove() }
})
