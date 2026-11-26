import { agentHandlers } from './agents'
import { appealHandlers } from './appeals'
import { auditLogHandlers } from './auditLogs'
import { authHandlers } from './auth'
import { dashboardHandlers } from './dashboard'
import { eventHandlers } from './events'
import { hashlistHandlers } from './hashlists'
import { policyHandlers } from './policies'
import { quarantineHandlers } from './quarantine'
import { rulesetHandlers } from './rulesets'
import { sampleHandlers } from './samples'

export const handlers = [
  ...authHandlers,
  ...agentHandlers,
  ...dashboardHandlers,
  ...eventHandlers,
  ...sampleHandlers,
  ...appealHandlers,
  ...quarantineHandlers,
  ...hashlistHandlers,
  ...policyHandlers,
  ...rulesetHandlers,
  ...auditLogHandlers,
]
