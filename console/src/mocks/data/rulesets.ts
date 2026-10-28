import type { RulesetItem, YaraRuleItem } from '../../lib/api/rulesets'

// 서버는 v0(내장 기본 룰셋)을 목록에서 숨기므로 mock도 v2부터 시작한다.
export const mockRulesets: RulesetItem[] = [
  {
    version: 4, ruleCount: 2, disabledRuleCount: 0, isActive: false,
    createdAt: '2026-10-27T09:00:00+09:00', activatedAt: null,
  },
  {
    version: 3, ruleCount: 3, disabledRuleCount: 1, isActive: true,
    createdAt: '2026-10-20T09:00:00+09:00', activatedAt: '2026-10-21T10:00:00+09:00',
  },
  {
    version: 2, ruleCount: 2, disabledRuleCount: 0, isActive: false,
    createdAt: '2026-10-10T09:00:00+09:00', activatedAt: '2026-10-11T10:00:00+09:00',
  },
]

export const mockRulesByVersion: Record<number, YaraRuleItem[]> = {
  4: [
    { ruleId: 'v4-packer', ruleName: 'Suspicious_Packer', severity: 'MEDIUM', enabled: true },
    { ruleId: 'v4-ransom', ruleName: 'Ransom_Note_Strings', severity: 'CRITICAL', enabled: true },
  ],
  3: [
    { ruleId: 'v3-dropper', ruleName: 'Generic_Dropper', severity: 'HIGH', enabled: true },
    { ruleId: 'v3-miner', ruleName: 'Coin_Miner_Config', severity: 'LOW', enabled: false },
    { ruleId: 'v3-packer', ruleName: 'Suspicious_Packer', severity: 'MEDIUM', enabled: true },
  ],
  2: [
    { ruleId: 'v2-dropper', ruleName: 'Generic_Dropper', severity: 'HIGH', enabled: true },
    { ruleId: 'v2-packer', ruleName: 'Suspicious_Packer', severity: 'MEDIUM', enabled: true },
  ],
}
