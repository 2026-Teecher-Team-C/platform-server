import { createContext } from 'react'
import type { CurrentAdmin, LoginRequest } from '../lib/api/auth'

export type AuthContextValue = {
  user: CurrentAdmin | null
  isInitializing: boolean
  isAuthenticated: boolean
  signIn: (credentials: LoginRequest) => Promise<CurrentAdmin>
  signOut: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)
