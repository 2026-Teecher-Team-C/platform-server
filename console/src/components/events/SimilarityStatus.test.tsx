import { renderToStaticMarkup } from 'react-dom/server'
import { expect, it } from 'vitest'
import { SimilarityEvidence } from './SimilarityEvidence'
import type { EventSimilarity } from '../../lib/api/events'

it.each([
  ['BLOCK', 0, 'block', '×', '유사도 차단'],
  ['BLOCK', 30, 'block', '×', '유사도 차단'],
  ['WARN', 31, 'warn', '!', '유사도 경고'],
  ['WARN', 70, 'warn', '!', '유사도 경고'],
  ['NONE', 71, 'success', '✓', 'TLSH 추가 조치 없음'],
  ['NONE', null, 'neutral', '?', '비교 가능한 기준 샘플 없음'],
  ['UNAVAILABLE', null, 'neutral', '?', 'TLSH 계산 불가'],
  [null, null, 'neutral', '?', '기록된 유사도 평가 없음'],
] as const)('%s %s의 아이콘과 접근성 이름', (classification, distance, tone, symbol, label) => {
  const similarity: EventSimilarity | null = classification === null ? null : {
    classification, distance, matchedSampleSha256: null, assessedAt: '2026-10-23T00:00:00Z',
  }
  const html = renderToStaticMarkup(<SimilarityEvidence similarity={similarity} />)
  expect(html).toContain(`event-similarity-icon--${tone}" role="img" aria-label="${label}" title="${label}">${symbol}</span></h2>`)
  expect(html).toContain('초록색 체크는 파일의 안전성을 입증하지 않습니다')
})
