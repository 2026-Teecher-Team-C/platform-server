/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync(new URL('../index.css', import.meta.url), 'utf8')

// 실제 크기와 모바일 배치는 브라우저에서 확인하고, 여기서는 화면별 크기 재분기를 막는다.
function declarationsFor(selector: string) {
  return [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .filter((match) => match[1].split(',').map((part) => part.trim()).includes(selector))
    .map((match) => match[2]).join('\n')
}

describe('관리 화면 제목 크기', () => {
  it('제목 옆 동작 버튼은 좁은 화면에서도 글자가 쪼개지지 않는다', () => {
    const actions = declarationsFor('.page-heading .events-button')
    expect(actions).toContain('flex-shrink: 0')
    expect(actions).toContain('white-space: nowrap')
  })

  it('일반 페이지, 대시보드와 화이트리스트가 같은 반응형 제목 크기를 사용한다', () => {
    expect(css.includes('--page-title-size: clamp(36px, 3.5vw, 44px)')).toBe(true)
    for (const selector of ['.page-heading h1', '.dashboard-header h1', '.page-heading .whitelist-title']) {
      const sizes = [...declarationsFor(selector).matchAll(/font-size:\s*([^;]+);/g)]
      expect(sizes.length).toBeGreaterThan(0)
      expect(sizes.at(-1)?.[1]).toBe('var(--page-title-size)')
    }
    expect(declarationsFor('.page-heading .eyebrow')).toContain('font-size: 16px')
    expect(css).not.toContain('.page-heading.quarantine-page-heading h1')
  })
})
