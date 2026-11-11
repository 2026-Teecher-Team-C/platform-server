import { createContext } from 'react'
import type { CurrentAdmin, LoginRequest } from '../lib/api/auth'

export type AuthContextValue = {
  user: CurrentAdmin | null
  isInitializing: boolean
  authNotice: string
  isAuthenticated: boolean
  signIn: (credentials: LoginRequest) => Promise<CurrentAdmin>
  signOut: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
