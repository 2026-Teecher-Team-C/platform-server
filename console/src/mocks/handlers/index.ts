import { agentHandlers } from './agents'
import { auditLogHandlers } from './auditLogs'
import { authHandlers } from './auth'
import { dashboardHandlers } from './dashboard'
import { eventHandlers } from './events'
import { hashlistHandlers } from './hashlists'
import { policyHandlers } from './policies'
import { quarantineHandlers } from './quarantine'
import { rulesetHandlers } from './rulesets'

export const handlers = [
  ...authHandlers,
  ...agentHandlers,
  ...dashboardHandlers,
  ...eventHandlers,
  ...quarantineHandlers,
  ...hashlistHandlers,
  ...policyHandlers,
  ...rulesetHandlers,
  ...auditLogHandlers,
]
