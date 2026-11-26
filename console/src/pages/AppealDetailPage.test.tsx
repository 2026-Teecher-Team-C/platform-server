// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AppealDetailPage } from './AppealDetailPage'
import { approveAppeal, getAdminAppeal, rejectAppeal, type AdminAppealDetail } from '../lib/api/adminAppeals'
import { ApiError } from '../lib/api/client'

const auth = vi.hoisted(() => ({ role: 'ADMIN' as 'ADMIN' | 'ANALYST' }))
vi.mock('../app/useAuth', () => ({ useAuth: () => ({ user: { username: 'admin', role: auth.role } }) }))
vi.mock('../lib/api/adminAppeals', () => ({ getAdminAppeal: vi.fn(), approveAppeal: vi.fn(), rejectAppeal: vi.fn() }))
const get = vi.mocked(getAdminAppeal)
const approve = vi.mocked(approveAppeal)
const reject = vi.mocked(rejectAppeal)

const appealId = '50000000-0000-4000-8000-000000000001'
const pending: AdminAppealDetail = {
  appealId, status: 'PENDING', createdAt: '2026-11-26T01:12:00Z', decidedAt: null, hostname: 'C2-PC',
  filename: 'report.exe', sha256: '9b'.repeat(32), evidenceKind: 'SIMILARITY', threat: 'Mirai',
  reasonPreview: '거래처에서 받은 납품 프로그램입니다.', eventId: '0f8c2b9e-4d1a-4c7b-9a2e-5b3f6d7e8a91',
  agentId: '30000000-0000-4000-8000-000000000001', reason: '거래처에서 받은 납품 프로그램입니다. 업무에 필요합니다.',
  decisionNote: null, decidedBy: null, blacklisted: false, blockedAt: '2026-11-26T01:11:00Z',
  similarity: { classification: 'BLOCK', distance: 12, matchedSampleSha256: '3f1a'.repeat(16) },
}
const approved: AdminAppealDetail = {
  ...pending, status: 'APPROVED', decidedAt: '2026-11-26T01:20:00Z', decidedBy: 'admin', decisionNote: '납품 확인',
}
const rejected: AdminAppealDetail = {
  ...pending, status: 'REJECTED', decidedAt: '2026-11-26T01:20:00Z', decidedBy: 'admin', decisionNote: '업무 승인 대상이 아닙니다.',
}

const APPROVE_TEXT = '이 해시를 화이트리스트에 등록합니다. 회사 모든 PC에서 이 파일이 통과합니다.'
const BLACKLIST_TEXT = '블랙리스트에서 해제됩니다.'

let root: Root
let container: HTMLDivElement
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  auth.role = 'ADMIN'
  get.mockReset(); approve.mockReset(); reject.mockReset()
  get.mockResolvedValue(pending)
  approve.mockResolvedValue(approved)
  reject.mockResolvedValue(rejected)
  container = document.createElement('div'); document.body.append(container); root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove() })

async function mount() {
  await act(async () => root.render(
    <MemoryRouter initialEntries={['/appeals/' + appealId]}>
      <Routes><Route path="/appeals/:appealId" element={<AppealDetailPage />} /></Routes>
    </MemoryRouter>,
  ))
}
function findButton(text: string) {
  return [...container.querySelectorAll('button')].find((b) => b.textContent === text)
}
function button(text: string) {
  const node = findButton(text)
  if (!node) throw new Error('버튼 없음: ' + text)
  return node
}
async function click(text: string) { await act(async () => button(text).click()) }
async function setNote(value: string) {
  await act(async () => {
    const input = container.querySelector('textarea')!
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
}
const panel = () => container.querySelector('[aria-label="소명 결정"]')

it('판정 근거·파일·PC·직원 사유를 보여 준다', async () => {
  await mount()
  expect(get).toHaveBeenCalledWith(appealId)
  const text = container.textContent ?? ''
  expect(text).toContain('검토 대기')
  expect(text).toContain('알려진 악성코드와 매우 비슷한 파일입니다.')
  expect(text).toContain('Mirai')
  expect(text).toContain('유사도 거리 12')
  expect(text).toContain('3f1a'.repeat(16))
  expect(text).toContain('report.exe')
  expect(text).toContain('9b'.repeat(32))
  expect(text).toContain('C2-PC')
  expect(text).toContain('거래처에서 받은 납품 프로그램입니다. 업무에 필요합니다.')
  expect(text).toContain('0f8c2b9e')
})

it('근거 스냅숏이 없으면 판정 근거 없음으로 표시한다', async () => {
  get.mockResolvedValue({ ...pending, evidenceKind: null, threat: null, similarity: null })
  await mount()
  expect(container.textContent).toContain('판정 근거 없음')
})

it('승인을 누르면 영향 설명에 포커스를 두고, 블랙리스트가 아니면 해제 문구는 없다', async () => {
  await mount()
  await click('승인')
  const form = container.querySelector('form[aria-label="소명 승인 확인"]')!
  expect(document.activeElement).toBe(form)
  expect(form.textContent).toContain(APPROVE_TEXT)
  expect(form.textContent).not.toContain(BLACKLIST_TEXT)
  expect(approve).not.toHaveBeenCalled()
})

it('블랙리스트에 있는 해시를 승인하면 해제 문구를 함께 보여 준다', async () => {
  get.mockResolvedValue({ ...pending, blacklisted: true })
  await mount()
  await click('승인')
  const form = container.querySelector('form[aria-label="소명 승인 확인"]')!
  expect(form.textContent).toContain(APPROVE_TEXT)
  expect(form.textContent).toContain(BLACKLIST_TEXT)
})

it('승인을 확인하면 승인 상태·결정자·의견을 보여 주고 버튼을 없앤다', async () => {
  await mount()
  await click('승인')
  await setNote('  납품 확인  ')
  await click('승인 확인')
  expect(approve).toHaveBeenCalledWith(appealId, '  납품 확인  ')
  expect(approve).toHaveBeenCalledTimes(1)
  const text = container.textContent ?? ''
  expect(container.querySelector('.event-detail-heading .events-badge')?.textContent).toBe('승인')
  expect(text).toContain('admin')
  expect(text).toContain('납품 확인')
  expect(findButton('승인')).toBeUndefined()
  expect(findButton('거절')).toBeUndefined()
  expect(container.querySelector('[role=status]')?.textContent).toContain('화이트리스트에 등록했습니다')
})

it('승인 확인을 취소하면 요청을 보내지 않는다', async () => {
  await mount()
  await click('승인')
  await click('취소')
  expect(approve).not.toHaveBeenCalled()
  expect(button('승인').disabled).toBe(false)
})

it('거절은 의견이 있어야 확인할 수 있고, 거절 뒤 의견과 상태를 보여 준다', async () => {
  await mount()
  await click('거절')
  expect(button('거절 확인').disabled).toBe(true)
  await setNote('   ')
  expect(button('거절 확인').disabled).toBe(true)
  await setNote('업무 승인 대상이 아닙니다.')
  expect(button('거절 확인').disabled).toBe(false)
  await click('거절 확인')
  expect(reject).toHaveBeenCalledWith(appealId, '업무 승인 대상이 아닙니다.')
  expect(container.querySelector('.event-detail-heading .events-badge')?.textContent).toBe('거절')
  expect(container.textContent).toContain('업무 승인 대상이 아닙니다.')
  expect(findButton('승인')).toBeUndefined()
  expect(findButton('거절')).toBeUndefined()
})

it('이미 결정된 소명에는 승인·거절 버튼이 없다', async () => {
  get.mockResolvedValue(approved)
  await mount()
  expect(container.querySelector('.event-detail-heading .events-badge')?.textContent).toBe('승인')
  expect(findButton('승인')).toBeUndefined()
  expect(findButton('거절')).toBeUndefined()
  expect(container.textContent).toContain('납품 확인')
})

it('ANALYST에게는 승인·거절 버튼이 없다', async () => {
  auth.role = 'ANALYST'
  await mount()
  expect(container.textContent).toContain('report.exe')
  expect(findButton('승인')).toBeUndefined()
  expect(findButton('거절')).toBeUndefined()
})

it('다른 담당자가 먼저 결정했으면(409) 안내하고 상태를 새로고침할 수 있다', async () => {
  approve.mockRejectedValueOnce(new ApiError(409, { code: 'CONFLICT', message: 'appeal already decided' }))
  await mount()
  await click('승인')
  await click('승인 확인')
  expect(container.querySelector('[role=alert]')?.textContent).toContain('다른 담당자가 먼저 처리했습니다')
  expect(button('승인 확인').disabled).toBe(true)
  get.mockResolvedValue(rejected)
  await click('상태 새로고침')
  expect(get).toHaveBeenCalledTimes(2)
  expect(container.querySelector('.event-detail-heading .events-badge')?.textContent).toBe('거절')
  expect(findButton('승인 확인')).toBeUndefined()
  expect(container.querySelector('[role=alert]')).toBeNull()
})

it('의견이 서버 기준에 맞지 않으면(400) 입력을 유지하고 다시 보낼 수 있다', async () => {
  reject.mockRejectedValueOnce(new ApiError(400, { code: 'INVALID_INPUT' }))
  await mount()
  await click('거절')
  await setNote('의견')
  await click('거절 확인')
  expect(container.querySelector('[role=alert]')?.textContent).toContain('1자 이상 1000자 이하')
  expect(container.querySelector('textarea')?.value).toBe('의견')
  expect(button('거절 확인').disabled).toBe(false)
})

it('연속으로 눌러도 승인 요청은 한 번만 보낸다', async () => {
  let finish!: (value: AdminAppealDetail) => void
  approve.mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
  await mount()
  await click('승인')
  await act(async () => { const confirm = button('승인 확인'); confirm.click(); confirm.click() })
  expect(approve).toHaveBeenCalledTimes(1)
  await act(async () => finish(approved))
})

it('직원 사유와 파일명은 HTML로 해석하지 않는다', async () => {
  get.mockResolvedValue({ ...pending, reason: '<b>x</b>', filename: '<i>y</i>.exe' })
  await mount()
  expect(container.querySelector('dd b')).toBeNull()
  expect(container.querySelector('i')).toBeNull()
  expect(container.textContent).toContain('<b>x</b>')
  expect(container.textContent).toContain('<i>y</i>.exe')
})

it('없는 소명이면 찾을 수 없다고 안내한다', async () => {
  get.mockRejectedValue(new ApiError(404, {}))
  await mount()
  expect(container.querySelector('[role=alert]')?.textContent).toContain('소명 요청을 찾을 수 없습니다')
  expect(panel()).toBeNull()
})
