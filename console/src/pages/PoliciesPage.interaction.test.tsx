// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PoliciesPage } from './PoliciesPage'
import { ApiError } from '../lib/api/client'
import { createBypassDomain, deactivateBypassDomain, getBypassDomains } from '../lib/api/policies'
import type { BypassDomainItem } from '../lib/api/policies'

vi.mock('../lib/api/policies', () => ({ getBypassDomains: vi.fn(), createBypassDomain: vi.fn(), deactivateBypassDomain: vi.fn() }))
const row: BypassDomainItem = { bypassId: '50000000-0000-4000-8000-000000000001', host: 'update.example.com', category: 'PINNED', reason: '피닝', isActive: true, createdAt: '2026-10-01T00:00:00Z' }
const page = (content = [row], number = 0, total = content.length) => ({ content, page: number, size: 20, totalElements: total, totalPages: Math.ceil(total / 20) })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
let container: HTMLDivElement
let root: Root
const text = () => container.textContent ?? ''
const button = (name: string) => [...container.querySelectorAll('button')].find((el) => el.textContent === name)!
const field = (name: string) => container.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(`[aria-label="${name}"]`)!
async function input(name: string, value: string) {
  await act(async () => {
    const el = field(name)
    const proto = el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
    Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(el, value)
    el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }))
  })
}
async function click(name: string) { await act(async () => button(name).click()) }
async function mount() { await act(async () => root.render(<PoliciesPage />)) }
async function draft(host = ' NEW.Example.com. ') { await input('등록 도메인', host); await input('등록 사유', ' 등록 사유 ') }

beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  vi.resetAllMocks()
  vi.mocked(getBypassDomains).mockResolvedValue(page())
  container = document.createElement('div'); document.body.append(container); root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); container.remove(); vi.useRealTimers() })

describe('바이패스 도메인 mounted 쓰기', () => {
  it.each(['등록 도메인', '도메인 검색어'])('%s 지우기는 해당 입력만 비우고 요청 없이 포커스를 돌린다', async (name) => {
    await mount(); await draft(); await input('등록 카테고리', 'SECURITY_UPDATE')
    await act(async () => {
      const search = container.querySelectorAll('form')[1].querySelector('input')!
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(search, '검색 초안')
      search.dispatchEvent(new Event('input', { bubbles: true }))
    })
    const clear = container.querySelector<HTMLButtonElement>(`[aria-label="${name} 지우기"]`)
    expect(clear).not.toBeNull()
    expect(clear!.type).toBe('button')
    await act(async () => clear!.click())
    expect(field(name).value).toBe('')
    expect(document.activeElement).toBe(field(name))
    expect(clear!.disabled).toBe(true)
    expect(field(name === '등록 도메인' ? '도메인 검색어' : '등록 도메인').value).toBe(name === '등록 도메인' ? '검색 초안' : ' NEW.Example.com. ')
    expect(field('등록 사유').value).toBe(' 등록 사유 ')
    expect(field('등록 카테고리').value).toBe('SECURITY_UPDATE')
    expect(getBypassDomains).toHaveBeenCalledTimes(1)
    expect(createBypassDomain).not.toHaveBeenCalled()
  })

  it.each(['등록', '비활성화'])('%s 결과가 불명확하고 GET도 실패하면 쓰기 잠금과 등록 초안을 유지한다', async (action) => {
    vi.mocked(createBypassDomain).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    vi.mocked(deactivateBypassDomain).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await mount(); await draft()
    if (action === '등록') await click('등록')
    else { await click('비활성화'); await input('비활성화 확인 도메인', row.host); await click('비활성화 확인'); await click('취소') }
    vi.mocked(getBypassDomains).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await click('새로고침')
    expect(button('등록').disabled).toBe(true)
    expect(button('비활성화').disabled).toBe(true)
    expect(field('등록 도메인').value).toBe(' NEW.Example.com. ')
    expect(field('등록 사유').value).toBe(' 등록 사유 ')
    await click('새로고침')
    expect(button('등록').disabled).toBe(false)
    expect(button('비활성화').disabled).toBe(false)
  })

  it('성공 알림은 닫을 수 있고 3초 뒤 자동으로 사라진다', async () => {
    vi.mocked(createBypassDomain).mockResolvedValue(row)
    await mount(); vi.useFakeTimers(); await draft(); await click('등록')
    expect(container.querySelector('.bypass-notice')).not.toBeNull()
    await act(async () => vi.advanceTimersByTime(3000))
    expect(container.querySelector('.bypass-notice')).toBeNull()
    await draft(); await click('등록')
    const close = container.querySelector<HTMLButtonElement>('[aria-label="알림 닫기"]')!
    expect(close).not.toBeNull()
    await act(async () => close.click())
    expect(container.querySelector('.bypass-notice')).toBeNull()
  })

  it('처리 여부가 불명확하면 새로 조회하기 전에는 쓰기를 다시 보내지 않는다', async () => {
    vi.mocked(createBypassDomain).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await mount(); await draft(); await click('등록')
    expect(field('등록 도메인').value).toBe(' NEW.Example.com. ')
    expect(button('등록').disabled).toBe(true)
    expect(button('비활성화').disabled).toBe(true)
    await click('등록')
    expect(createBypassDomain).toHaveBeenCalledTimes(1)
    await click('새로고침')
    expect(getBypassDomains).toHaveBeenCalledTimes(2)
    expect(button('등록').disabled).toBe(false)
  })

  it.each(['등록 성공', '등록 실패', '해제 성공', '해제 실패'])('unmount 뒤 %s 응답은 재조회하거나 새 화면을 변경하지 않는다', async (scenario) => {
    const pending = deferred<BypassDomainItem>()
    if (scenario.startsWith('등록')) vi.mocked(createBypassDomain).mockReturnValue(pending.promise)
    else vi.mocked(deactivateBypassDomain).mockImplementation(() => pending.promise.then(() => undefined))
    await mount()
    if (scenario.startsWith('등록')) { await draft(); await click('등록') }
    else { await click('비활성화'); await input('비활성화 확인 도메인', row.host); await click('비활성화 확인') }
    await act(async () => root.unmount())
    root = createRoot(container)
    await mount()
    await act(async () => { if (scenario.endsWith('성공')) pending.resolve(row); else pending.reject(new Error('늦은 오류')) })
    expect(getBypassDomains).toHaveBeenCalledTimes(2)
    expect(text()).not.toContain('등록했습니다')
    expect(text()).not.toContain('비활성화했습니다')
    expect(container.querySelector('[role="alert"]')).toBeNull()
  })

  it('해제 연속 제출을 잠그고 오류 후 취소해도 등록 초안은 남는다', async () => {
    const pending = deferred<void>()
    vi.mocked(deactivateBypassDomain).mockReturnValue(pending.promise)
    await mount(); await draft(); await click('비활성화'); await input('비활성화 확인 도메인', row.host)
    await act(async () => {
      const form = field('비활성화 확인 도메인').closest('form')!
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })
    expect(deactivateBypassDomain).toHaveBeenCalledTimes(1)
    expect(button('취소').disabled).toBe(true)
    expect(field('등록 도메인').disabled).toBe(true)
    await act(async () => pending.reject(new Error('통신 오류')))
    expect(text()).toContain('확인 입력을 유지')
    expect(field('비활성화 확인 도메인').value).toBe(row.host)
    await click('취소')
    expect(field('비활성화 확인 도메인')).toBeNull()
    expect(field('등록 도메인').value).toBe(' NEW.Example.com. ')
    expect(getBypassDomains).toHaveBeenCalledTimes(1)
  })

  it('등록 후 필터와 검색 초안을 초기화하고 크기를 유지한 적용 첫 페이지로 이동한다', async () => {
    vi.mocked(createBypassDomain).mockResolvedValue(row)
    vi.mocked(getBypassDomains).mockImplementation(async (query) => ({ ...page([row], query?.page ?? 0, 151), size: query?.size ?? 20 }))
    await mount()
    // 등록 폼과 목록 필터는 별도다. 기존 목록 폼의 컨트롤로 실제 change를 전달한다.
    const filters = container.querySelectorAll('form')[1]
    async function changeFilter(index: number, value: string) {
      await act(async () => {
        const el = filters.querySelectorAll('input, select')[index] as HTMLInputElement | HTMLSelectElement
        Object.getOwnPropertyDescriptor(el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype, 'value')!.set!.call(el, value)
        el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }))
      })
    }
    await changeFilter(0, 'update'); await changeFilter(1, 'SECURITY_UPDATE'); await changeFilter(2, 'false'); await click('조회')
    await act(async () => {
      const size = container.querySelector('.events-panel-heading select')!
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')!.set!.call(size, '50')
      size.dispatchEvent(new Event('change', { bubbles: true }))
    })
    await changeFilter(0, '아직 조회하지 않은 초안')
    await click('다음')
    expect(getBypassDomains).toHaveBeenLastCalledWith({ host: 'update', category: 'SECURITY_UPDATE', isActive: false, page: 1, size: 50 })
    await draft(); await click('등록')
    expect(getBypassDomains).toHaveBeenLastCalledWith({ isActive: true, page: 0, size: 50 })
    expect((filters.querySelector('input') as HTMLInputElement).value).toBe('')
    expect((filters.querySelectorAll('select')[0] as HTMLSelectElement).value).toBe('')
    expect((filters.querySelectorAll('select')[1] as HTMLSelectElement).value).toBe('true')
  })

  it.each(['성공', '실패'])('언마운트 전 GET의 늦은 %s가 새 화면과 새 로딩 상태를 바꾸지 않는다', async (outcome) => {
    const old = deferred<ReturnType<typeof page>>()
    const fresh = deferred<ReturnType<typeof page>>()
    vi.mocked(getBypassDomains).mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    await mount(); await act(async () => root.unmount()); root = createRoot(container); await mount()
    await act(async () => { if (outcome === '성공') old.resolve(page()); else old.reject(new Error('늦은 오류')) })
    expect(text()).toContain('불러오는 중')
    expect(text()).not.toContain(row.host)
    await act(async () => fresh.resolve(page([{ ...row, host: 'fresh.example.com' }])))
    expect(text()).toContain('fresh.example.com')
    expect(container.querySelector('[role="alert"]')).toBeNull()
  })

  it('재조회 200ms 진행 표시, 실패 시 이전 결과 유지와 재시도를 보존한다', async () => {
    await mount()
    vi.useFakeTimers()
    const pending = deferred<ReturnType<typeof page>>()
    vi.mocked(getBypassDomains).mockReturnValueOnce(pending.promise)
    await click('새로고침')
    expect(text()).toContain(row.host)
    expect(text()).not.toContain('조회 중…')
    await act(async () => vi.advanceTimersByTime(199))
    expect(text()).not.toContain('조회 중…')
    await act(async () => vi.advanceTimersByTime(1))
    expect(text()).toContain('조회 중… (이전 결과)')
    await act(async () => pending.reject(new Error('조회 실패')))
    expect(text()).toContain('이전 조회 결과')
    expect(text()).toContain(row.host)
    expect(button('비활성화').disabled).toBe(true)
    await click('다시 시도')
    expect(getBypassDomains).toHaveBeenCalledTimes(3)
    expect(container.querySelector('[role="alert"]')).toBeNull()
  })

  it('마지막 페이지의 마지막 행 비활성화 후 범위를 벗어나면 마지막 유효 페이지를 조회한다', async () => {
    vi.mocked(getBypassDomains).mockResolvedValueOnce(page([row], 0, 21)).mockResolvedValueOnce(page([row], 1, 21))
      .mockResolvedValueOnce(page([], 1, 20)).mockResolvedValueOnce(page([row], 0, 20))
    vi.mocked(deactivateBypassDomain).mockResolvedValue()
    await mount(); await click('다음'); await click('비활성화'); await input('비활성화 확인 도메인', row.host); await click('비활성화 확인')
    expect(getBypassDomains).toHaveBeenCalledTimes(4)
    expect(getBypassDomains).toHaveBeenLastCalledWith({ isActive: true, page: 0, size: 20 })
    expect(text()).toContain('1 / 1페이지')
  })

  it('같은 tick의 연속 submit과 쓰기 중 다른 작업을 잠그고 실패한 등록 초안을 보존한다', async () => {
    const pending = deferred<BypassDomainItem>()
    vi.mocked(createBypassDomain).mockReturnValue(pending.promise)
    await mount(); await draft()
    await act(async () => {
      const form = container.querySelector('form[aria-label="바이패스 도메인 등록"]')!
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })
    expect(createBypassDomain).toHaveBeenCalledTimes(1)
    expect(button('새로고침').disabled).toBe(true)
    expect(button('조회').disabled).toBe(true)
    expect(button('비활성화').disabled).toBe(true)
    await act(async () => pending.reject(new ApiError(409, {})))
    expect(field('등록 도메인').value).toBe(' NEW.Example.com. ')
    expect(field('등록 사유').value).toBe(' 등록 사유 ')
    expect(text()).toContain('이미 적용 중인')
    expect(getBypassDomains).toHaveBeenCalledTimes(1)
    expect(button('등록').disabled).toBe(false)
  })

  it('잘못된 입력은 전송하지 않고, 등록 성공 시 성공한 입력만 비우고 목록을 재조회한다', async () => {
    vi.mocked(createBypassDomain).mockResolvedValue(row)
    await mount()
    await draft('*.example.com')
    await click('등록')
    expect(createBypassDomain).not.toHaveBeenCalled()
    expect(text()).toContain('와일드카드')
    await input('등록 도메인', ' NEW.Example.com. ')
    await click('등록')
    expect(createBypassDomain).toHaveBeenCalledWith({ host: 'new.example.com', category: 'PINNED', reason: ' 등록 사유 ' })
    expect(field('등록 도메인').value).toBe('')
    expect(field('등록 사유').value).toBe('')
    expect(getBypassDomains).toHaveBeenCalledTimes(2)
    expect(getBypassDomains).toHaveBeenLastCalledWith({ isActive: true, page: 0, size: 20 })
  })

  it('적용 행만 대상 확인을 거쳐 비활성화하고 실패 시 대상과 확인 입력을 보존한다', async () => {
    vi.mocked(getBypassDomains).mockResolvedValue(page([row, { ...row, bypassId: 'inactive', host: 'inactive.example.com', isActive: false }]))
    vi.mocked(deactivateBypassDomain).mockRejectedValueOnce(new ApiError(404, { code: 'NOT_FOUND' })).mockResolvedValueOnce()
    await mount()
    expect([...container.querySelectorAll('button')].filter((el) => el.textContent === '비활성화')).toHaveLength(1)
    await click('비활성화')
    expect(text()).toContain('다시 검사 대상')
    expect(deactivateBypassDomain).not.toHaveBeenCalled()
    expect(button('비활성화 확인').disabled).toBe(true)
    await input('비활성화 확인 도메인', row.host)
    await click('비활성화 확인')
    expect(field('비활성화 확인 도메인').value).toBe(row.host)
    expect(text()).toContain('이미 미적용')
    await click('비활성화 확인')
    expect(deactivateBypassDomain).toHaveBeenCalledTimes(2)
    expect(deactivateBypassDomain).toHaveBeenLastCalledWith(row.bypassId)
    expect(field('비활성화 확인 도메인')).toBeNull()
    expect(getBypassDomains).toHaveBeenCalledTimes(2)
  })
})
