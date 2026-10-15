import type { RulesetListItem } from '../../lib/api/rulesets'

export const mockRulesets: RulesetListItem[] = [
  {
    rulesetId: '70000000-0000-4000-8000-000000000001',
    version: '2026.10.10',
    ruleCount: 42,
    isActive: true,
    createdAt: '2026-10-10T09:00:00+09:00',
  },
  {
    rulesetId: '70000000-0000-4000-8000-000000000002',
    version: '2026.10.01',
    ruleCount: 38,
    isActive: false,
    createdAt: '2026-10-01T09:00:00+09:00',
  },
]
