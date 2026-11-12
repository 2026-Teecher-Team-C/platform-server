import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { expect, it } from 'vitest'
import { SimilarityEvidence } from './SimilarityEvidence'
import type { EventSimilarity } from '../../lib/api/events'

const restrictedSimilarity: EventSimilarity = { classification: 'WARN', distance: 45, matchedSampleSha256: 'ab'.repeat(32), assessedAt: '2026-10-23T00:00:00Z' }

it('권한 prop을 빠뜨리면 SHA는 보존하되 기본적으로 샘플 링크를 허용하지 않는다', () => {
  const html = renderToStaticMarkup(<MemoryRouter><SimilarityEvidence similarity={restrictedSimilarity} /></MemoryRouter>)
  expect(html).toContain(restrictedSimilarity.matchedSampleSha256)
  expect(html).not.toContain('href=')
  expect(html).toContain('샘플 상세는 ADMIN만 조회할 수 있습니다.')
})

it('접힌 설명 안에 모든 아이콘의 거리 구간과 기록 없는 경우를 안내한다', () => {
  const html = renderToStaticMarkup(<SimilarityEvidence similarity={null} />)
  expect(html).toContain('아이콘과 거리 기준')
  expect(html).toContain('0~30: 유사도 차단')
  expect(html).toContain('31~70: 유사도 경고')
  expect(html).toContain('70 초과: 추가 조치 없음')
  expect(html).toContain('거리 없음: 별도 확인 필요')
  expect(html).toContain('기준 샘플 없음, TLSH 계산 불가, 평가 기록 없음을 구분합니다.')
  expect(html).not.toContain('100.70.241.6')
})

it.each([
  ['BLOCK', 17, '유사도 차단'],
  ['NONE', 120, '경고 범위에 해당하는 유사 샘플 없음'],
  ['NONE', null, '비교 가능한 기준 샘플 없음'],
  ['UNAVAILABLE', null, 'TLSH 계산 불가'],
  [null, null, '기록된 유사도 평가 없음'],
] as const)('%s, 거리 %s의 의미를 구분한다', (classification, distance, message) => {
  const similarity: EventSimilarity | null = classification === null ? null : { classification, distance,
    matchedSampleSha256: distance === null ? null : 'ab'.repeat(32), assessedAt: '2026-10-23T00:00:00Z' }
  const html = renderToStaticMarkup(<MemoryRouter><SimilarityEvidence similarity={similarity} /></MemoryRouter>)
  expect(html).toContain(message)
  if (classification === null) expect(html).toContain('평가하지 않았거나 이 이벤트에 연결된 기록이 없습니다')
  if (distance !== null) expect(html).toContain('ab'.repeat(32))
  if (distance === null) expect(html).not.toContain('TLSH 거리</dt>')
})

it.each([
  ['BLOCK', 0, '거리 0은 차단 기준인 30 이하에 해당합니다.'],
  ['BLOCK', 30, '거리 30은 차단 기준인 30 이하에 해당합니다.'],
  ['WARN', 31, '거리 31은 경고 구간인 31~70에 해당합니다.'],
  ['WARN', 70, '거리 70은 경고 구간인 31~70에 해당합니다.'],
  ['NONE', 71, '거리 71은 경고 기준인 70을 초과합니다.'],
] as const)('%s 거리 %s에 해당하는 정책과 처리 이유를 설명한다', (classification, distance, explanation) => {
  const similarity: EventSimilarity = { classification, distance, matchedSampleSha256: 'ab'.repeat(32), assessedAt: '2026-10-23T00:00:00Z' }
  const html = renderToStaticMarkup(<MemoryRouter><SimilarityEvidence similarity={similarity} /></MemoryRouter>)
  expect(html).toContain(explanation)
  expect(html).toContain(`<dd>${distance}</dd>`)
  if (classification === 'BLOCK') expect(html).toContain('활성 블랙리스트의 악성 기준 샘플과 유사하여 SIMILARITY 출처로 차단한 평가입니다.')
  if (classification === 'WARN') expect(html).toContain('TLSH만으로는 차단하지 않고 유사 샘플 경고를 기록한 평가입니다.')
  if (classification === 'NONE') expect(html).toContain('TLSH로 추가 차단하거나 경고하지 않고 기존 검사 판정을 유지한 평가입니다.')
})
