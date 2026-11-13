// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { RecentBlocks } from './RecentBlocks'
import type { DownloadEventListItem } from '../../lib/api/events'

let container: HTMLDivElement
let root: Root
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })

function item(over: Partial<DownloadEventListItem> = {}): DownloadEventListItem {
  return {
    eventId: 'e1', agentId: 'a1', hostname: 'PC-01', sha256: 'abcdef0123456789', filename: 'setup.exe',
    mimeType: null, fileSize: null, decision: 'BLOCKED', decisionSource: 'ENGINE', cacheHit: false,
    holdDurationMs: null, similarityClassification: null,
    createdAt: new Date(2026, 10, 8, 9, 5, 0).toISOString(), severity: 'HIGH',
    topRuleName: 'Emotet', extraRuleCount: 2, acknowledged: false, ...over,
  }
}

async function mountRecent(rows: DownloadEventListItem[] | null, newIds: string[] = []) {
  await act(async () => root.render(
    <MemoryRouter><RecentBlocks rows={rows} newIds={new Set(newIds)} /></MemoryRouter>,
  ))
}

describe('RecentBlocks', () => {
  it('행과 전체 링크를 보여 준다', async () => {
    await mountRecent([item()])
    expect(container.textContent).toContain('최근 차단')
    expect(container.textContent).toContain('11/8 09:05')
    expect(container.textContent).toContain('Emotet 외 2건')
    expect(container.textContent).toContain('High')
    expect(container.textContent).toContain('탐지 엔진')
    expect(container.querySelector('a')?.getAttribute('href')).toBe('/events?decision=BLOCKED&decision=FAIL_CLOSE')
  })

  it('FAIL_CLOSE 행은 파일명과 출처가 없어도 검사 불가로 표시한다', async () => {
    await mountRecent([item({
      decision: 'FAIL_CLOSE', filename: null, sha256: null, decisionSource: null, severity: null,
      topRuleName: null, extraRuleCount: 0,
    })])
    const cells = [...container.querySelectorAll('tbody td')].map((td) => td.textContent)
    expect(cells).toContain('검사 불가')
    expect(cells.filter((c) => c === '—')).toHaveLength(3)
  })

  it('파일명이 없으면 해시 앞 12자를 보인다', async () => {
    await mountRecent([item({ filename: null, sha256: 'abcdef0123456789' })])
    expect(container.textContent).toContain('abcdef012345…')
  })

  it('새로 들어온 행을 강조한다', async () => {
    await mountRecent([item({ eventId: 'n1' }), item({ eventId: 'o1' })], ['n1'])
    const rows = container.querySelectorAll('tbody tr')
    expect(rows[0].classList.contains('is-new')).toBe(true)
    expect(rows[1].classList.contains('is-new')).toBe(false)
  })

  it('빈 목록이면 빈 상태 문구', async () => {
    await mountRecent([])
    expect(container.textContent).toContain('이 기간에 차단이 없습니다')
  })
})
