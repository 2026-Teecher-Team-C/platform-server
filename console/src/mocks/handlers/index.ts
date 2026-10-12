import { agentHandlers } from './agents'
import { auditLogHandlers } from './auditLogs'
import { authHandlers } from './auth'
import { eventHandlers } from './events'
import { hashlistHandlers } from './hashlists'
import { policyHandlers } from './policies'
import { quarantineHandlers } from './quarantine'
import { rulesetHandlers } from './rulesets'
import { statsHandlers } from './stats'

export const handlers = [
  ...authHandlers,
  ...agentHandlers,
  ...eventHandlers,
  ...quarantineHandlers,
  ...hashlistHandlers,
  ...policyHandlers,
  ...rulesetHandlers,
  ...auditLogHandlers,
  ...statsHandlers,
]
