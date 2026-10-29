import { afterAll, beforeAll, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { API_BASE_URL } from './client'
import { restoreQuarantineFile } from './quarantine'

const id = '10000000-0000-4000-8000-000000000001'
let received: { method: string; body: string } | null = null
const server = setupServer(http.post(API_BASE_URL + '/api/admin/quarantine/:id/restore', async ({ request }) => {
  received = { method: request.method, body: await request.text() }
  return HttpResponse.json({ quarantineId: id, sha256: 'a'.repeat(64), status: 'RESTORED', whitelisted: true, restoredAt: '2026-10-29T00:00:00Z' })
}))
beforeAll(() => server.listen())
afterAll(() => server.close())

it('복원 사유가 있으면 앞뒤 공백을 제외한 JSON 본문을 기존 POST로 전달한다', async () => {
  await restoreQuarantineFile(id, '  오탐 검토 완료  ')
  expect(received?.method).toBe('POST')
  expect(JSON.parse(received!.body)).toEqual({ reason: '오탐 검토 완료' })
})

it.each([undefined, '  '])('복원 사유가 %s이면 본문을 생략해 서버 기본 사유를 사용한다', async (reason) => {
  await restoreQuarantineFile(id, reason)
  expect(received?.body).toBe('')
})
