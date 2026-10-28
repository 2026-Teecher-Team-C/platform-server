import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import type { RulesetItem, YaraRuleItem } from '../lib/api/rulesets'
import { RulesetRuleRow, RulesetVersionItem } from './RulesetsPage'

const noop = () => {}

const active: RulesetItem = {
  version: 3, ruleCount: 3, disabledRuleCount: 1, isActive: true,
  createdAt: '2026-10-27T00:00:00Z', activatedAt: '2026-10-27T01:00:00Z',
}

const rule: YaraRuleItem = { ruleId: 'r1', ruleName: 'Suspicious_Packer', severity: 'CRITICAL', enabled: true }

function renderRule(row: YaraRuleItem, isBusy = false) {
  return renderToStaticMarkup(<table><tbody>
    <RulesetRuleRow rule={row} isBusy={isBusy} onToggle={noop} />
  </tbody></table>)
}

describe('RulesetRuleRow', () => {
  it('켜진 룰은 스위치가 켜져 있고 심각도를 보여준다', () => {
    const html = renderRule(rule)
    expect(html).toContain('Suspicious_Packer')
    expect(html).toContain('CRITICAL')
    expect(html).toContain('role="switch"')
    expect(html).toContain('aria-checked="true"')
  })

  it('꺼진 룰은 aria-checked가 false다', () => {
    expect(renderRule({ ...rule, enabled: false })).toContain('aria-checked="false"')
  })

  it('처리 중에는 스위치가 비활성화된다', () => {
    expect(renderRule(rule, true)).toContain('disabled')
  })
})

describe('RulesetVersionItem', () => {
  it('활성 버전은 적용 중과 룰 수를 보여준다', () => {
    const html = renderToStaticMarkup(<RulesetVersionItem item={active} selected onSelect={noop} />)
    expect(html).toContain('v3')
    expect(html).toContain('적용 중')
    expect(html).toContain('룰 3개')
    expect(html).toContain('aria-current="true"')
  })

  it('선택되지 않은 대기 버전은 대기로 표시한다', () => {
    const html = renderToStaticMarkup(
      <RulesetVersionItem item={{ ...active, version: 4, isActive: false, activatedAt: null }} selected={false} onSelect={noop} />,
    )
    expect(html).toContain('대기')
    expect(html).not.toContain('aria-current')
  })
})
