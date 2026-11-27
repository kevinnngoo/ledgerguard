import { createContext, useContext, useState, type ReactNode } from 'react'
import { api, clearCredentials, setCredentials, type Me } from './api'

interface AuthState {
  me: Me | null
  login: (username: string, password: string) => Promise<void>
  logout: () => void
  hasRole: (role: string) => boolean
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)

  async function login(username: string, password: string) {
    setCredentials(username, password)
    try {
      setMe(await api.me())
    } catch (e) {
      clearCredentials()
      throw e
    }
  }

  function logout() {
    clearCredentials()
    setMe(null)
  }

  // Convenience for the UI only. The server enforces every permission independently.
  const hasRole = (role: string) => me?.roles.includes(role) ?? false

  return <AuthContext.Provider value={{ me, login, logout, hasRole }}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react/only-export-components
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
