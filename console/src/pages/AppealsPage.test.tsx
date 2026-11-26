// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AppealsPage } from './AppealsPage'
import { listAppeals, type AdminAppealListItem } from '../lib/api/adminAppeals'
import type { PageResponse } from '../lib/api/types'

vi.mock('../lib/api/adminAppeals', () => ({ listAppeals: vi.fn() }))
const list = vi.mocked(listAppeals)
const appealId = '50000000-0000-4000-8000-000000000001'
const row: AdminAppealListItem = {
  appealId, status: 'PENDING', createdAt: '2026-11-26T01:12:00Z', decidedAt: null, hostname: 'C2-PC',
  filename: 'report.exe', sha256: '9b'.repeat(32), evidenceKind: 'SIMILARITY', threat: 'Mirai',
  reasonPreview: '거래처에서 받은 납품 프로그램입니다.',
}
function pageOf(content: AdminAppealListItem[]): PageResponse<AdminAppealListItem> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 }
}

let root: Root
let container: HTMLDivElement
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  list.mockReset()
  list.mockResolvedValue(pageOf([row]))
  container = document.createElement('div'); document.body.append(container); root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })

function DetailProbe() {
  const { appealId: id } = useParams()
  return <p id="detail-probe">{id}</p>
}
async function mount() {
  await act(async () => root.render(
    <MemoryRouter initialEntries={['/appeals']}>
      <Routes>
        <Route path="/appeals" element={<AppealsPage />} />
        <Route path="/appeals/:appealId" element={<DetailProbe />} />
      </Routes>
    </MemoryRouter>,
  ))
}
function chip(text: string) {
  const node = [...container.querySelectorAll<HTMLButtonElement>('[aria-label="소명 상태"] button')]
    .find((b) => b.textContent?.startsWith(text))
  if (!node) throw new Error('필터 없음: ' + text)
  return node
}

it('기본 필터는 대기이고 접수 시각·PC·파일·위협·상태를 보여 준다', async () => {
  await mount()
  expect(list).toHaveBeenCalledWith({ status: 'PENDING', page: 0, size: 20 })
  expect(chip('대기').getAttribute('aria-pressed')).toBe('true')
  expect(chip('승인').getAttribute('aria-pressed')).toBe('false')
  const headers = [...container.querySelectorAll('th')].map((th) => th.textContent)
  expect(headers).toEqual(['접수 시각', 'PC', '파일', '위협', '상태'])
  const cells = [...container.querySelectorAll('tbody td')].map((td) => td.textContent)
  expect(cells).toContain('C2-PC')
  expect(cells).toContain('report.exe')
  expect(cells).toContain('Mirai (유사도)')
  expect(cells).toContain('검토 대기')
})

it.each([
  ['승인', 'APPROVED'],
  ['거절', 'REJECTED'],
  ['전체', undefined],
] as const)('%s 필터를 누르면 그 상태로 다시 조회한다', async (label, status) => {
  await mount()
  await act(async () => chip(label).click())
  expect(list).toHaveBeenLastCalledWith({ status, page: 0, size: 20 })
  expect(chip(label).getAttribute('aria-pressed')).toBe('true')
})

it('행을 누르면 소명 상세로 이동한다', async () => {
  await mount()
  await act(async () => container.querySelector<HTMLTableRowElement>('tbody tr')!.click())
  expect(container.querySelector('#detail-probe')?.textContent).toBe(appealId)
})

it('대기 중인 소명이 없으면 빈 목록 안내를 보여 준다', async () => {
  list.mockResolvedValue(pageOf([]))
  await mount()
  expect(container.textContent).toContain('검토 대기 중인 소명이 없습니다.')
  expect(container.querySelector('table')).toBeNull()
})

it('목록을 불러오지 못하면 다시 시도할 수 있다', async () => {
  list.mockRejectedValueOnce(new Error('down'))
  await mount()
  expect(container.querySelector('[role=alert]')?.textContent).toContain('소명 요청을 불러오지 못했습니다.')
  await act(async () => [...container.querySelectorAll('button')].find((b) => b.textContent === '다시 시도')!.click())
  expect(container.textContent).toContain('report.exe')
})

it('직원이 정한 파일명은 HTML로 해석하지 않는다', async () => {
  list.mockResolvedValue(pageOf([{ ...row, filename: '<b>x</b>.exe' }]))
  await mount()
  expect(container.querySelector('tbody b')).toBeNull()
  expect(container.textContent).toContain('<b>x</b>.exe')
})
