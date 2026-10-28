import { apiRequest } from './client'
import { toQueryString } from './types'
import type { PageResponse } from './types'

export type RulesetItem = {
  version: number
  ruleCount: number
  disabledRuleCount: number
  isActive: boolean
  createdAt: string
  activatedAt: string | null
}

export type YaraRuleSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'

export type YaraRuleItem = {
  ruleId: string
  ruleName: string
  severity: YaraRuleSeverity
  enabled: boolean
}

export function getRulesets(query: { page?: number, size?: number } = {}) {
  return apiRequest<PageResponse<RulesetItem>>('/api/admin/rulesets' + toQueryString(query))
}

export function getRulesetRules(version: number) {
  return apiRequest<PageResponse<YaraRuleItem>>('/api/admin/rulesets/' + version + '/rules')
}

export function uploadRuleset(file: File) {
  const body = new FormData()
  body.append('file', file)
  return apiRequest<RulesetItem>('/api/admin/rulesets', { method: 'POST', body })
}

export function activateRuleset(version: number) {
  return apiRequest<RulesetItem>('/api/admin/rulesets/' + version + '/activate', { method: 'POST' })
}

export function updateYaraRuleEnabled(version: number, ruleId: string, enabled: boolean) {
  return apiRequest<YaraRuleItem>(
    '/api/admin/rulesets/' + version + '/rules/' + encodeURIComponent(ruleId),
    { method: 'PATCH', body: JSON.stringify({ enabled }) },
  )
}
