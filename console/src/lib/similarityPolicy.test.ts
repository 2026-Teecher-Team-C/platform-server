import { describe, expect, it } from 'vitest'
import { parseSimilarityDraft, validateSimilarityRange } from './similarityPolicy'

// 서버가 주는 허용 범위와 같은 모양이다(차단 0~50, 경고는 차단보다 크고 70 이하).
const limits = { blockMin: 0, blockMax: 50, warnMax: 70 }

const BLOCK_MESSAGE = '차단 임계값은 0 이상 50 이하여야 합니다.'
const WARN_MESSAGE = '경고 임계값은 차단 임계값보다 크고 70 이하여야 합니다.'

describe('validateSimilarityRange', () => {
  it('기본값 30/70은 허용한다', () => {
    expect(validateSimilarityRange({ blockDistance: 30, warnDistance: 70 }, limits))
      .toEqual({ blockDistance: 30, warnDistance: 70 })
  })

  it('경계값은 허용한다', () => {
    // 차단 최솟값 0, 경고는 차단보다 1만 커도 된다.
    expect(() => validateSimilarityRange({ blockDistance: 0, warnDistance: 1 }, limits)).not.toThrow()
    // 차단 최댓값 50이어도 경고 51~70이 남는다.
    expect(() => validateSimilarityRange({ blockDistance: 50, warnDistance: 51 }, limits)).not.toThrow()
    expect(() => validateSimilarityRange({ blockDistance: 50, warnDistance: 70 }, limits)).not.toThrow()
  })

  it('차단 임계값이 범위를 벗어나면 거부한다', () => {
    expect(() => validateSimilarityRange({ blockDistance: -1, warnDistance: 70 }, limits)).toThrow(BLOCK_MESSAGE)
    expect(() => validateSimilarityRange({ blockDistance: 51, warnDistance: 70 }, limits)).toThrow(BLOCK_MESSAGE)
  })

  it('경고 임계값이 차단 이하이면 거부한다', () => {
    // 같은 값이면 WARN 구간이 한 칸도 없다.
    expect(() => validateSimilarityRange({ blockDistance: 30, warnDistance: 30 }, limits)).toThrow(WARN_MESSAGE)
    expect(() => validateSimilarityRange({ blockDistance: 40, warnDistance: 30 }, limits)).toThrow(WARN_MESSAGE)
  })

  it('경고 임계값이 상한 70을 넘으면 거부한다', () => {
    expect(() => validateSimilarityRange({ blockDistance: 30, warnDistance: 71 }, limits)).toThrow(WARN_MESSAGE)
  })

  it('정수가 아니면 거부한다', () => {
    expect(() => validateSimilarityRange({ blockDistance: 30.5, warnDistance: 70 }, limits)).toThrow(BLOCK_MESSAGE)
    expect(() => validateSimilarityRange({ blockDistance: 30, warnDistance: 70.5 }, limits)).toThrow(WARN_MESSAGE)
  })

  it('숫자를 박지 않고 서버가 준 limits를 따른다', () => {
    // 서버 상한이 바뀌어도 화면이 그대로 따라가야 한다.
    const narrow = { blockMin: 10, blockMax: 20, warnMax: 25 }
    expect(() => validateSimilarityRange({ blockDistance: 15, warnDistance: 25 }, narrow)).not.toThrow()
    expect(() => validateSimilarityRange({ blockDistance: 9, warnDistance: 20 }, narrow))
      .toThrow('차단 임계값은 10 이상 20 이하여야 합니다.')
  })
})

describe('parseSimilarityDraft', () => {
  it('문자열을 숫자로 바꾼다', () => {
    expect(parseSimilarityDraft({ blockDistance: '30', warnDistance: '70' }, limits))
      .toEqual({ blockDistance: 30, warnDistance: 70 })
  })

  it('앞뒤 공백은 무시한다', () => {
    expect(parseSimilarityDraft({ blockDistance: ' 30 ', warnDistance: ' 70 ' }, limits))
      .toEqual({ blockDistance: 30, warnDistance: 70 })
  })

  it('빈 입력은 어느 칸인지 알려 준다', () => {
    expect(() => parseSimilarityDraft({ blockDistance: '', warnDistance: '70' }, limits))
      .toThrow('차단 임계값을 입력해 주세요.')
    expect(() => parseSimilarityDraft({ blockDistance: '30', warnDistance: '   ' }, limits))
      .toThrow('경고 임계값을 입력해 주세요.')
  })

  it('정수가 아닌 입력은 거부한다', () => {
    for (const text of ['30.5', '-1', 'abc', '3e1']) {
      expect(() => parseSimilarityDraft({ blockDistance: text, warnDistance: '70' }, limits))
        .toThrow('차단 임계값은 0 이상의 정수로 입력해 주세요.')
    }
  })

  it('숫자로 바꾼 뒤에는 허용 범위 검사로 이어진다', () => {
    expect(() => parseSimilarityDraft({ blockDistance: '51', warnDistance: '70' }, limits)).toThrow(BLOCK_MESSAGE)
    expect(() => parseSimilarityDraft({ blockDistance: '30', warnDistance: '71' }, limits)).toThrow(WARN_MESSAGE)
  })
})