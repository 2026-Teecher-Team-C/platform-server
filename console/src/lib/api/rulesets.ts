import { apiRequest } from './client'

export type RulesetListItem = {
  rulesetId: string
  version: string
  ruleCount: number
  isActive: boolean
  createdAt: string
}

export type RulesetListResponse = {
  items: RulesetListItem[]
  nextCursor: string | null
}

export function getRulesets() {
  return apiRequest<RulesetListResponse>('/api/admin/rulesets')
}
