import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { pb } from './pb.ts'
import type { UserRecord } from './types.ts'

interface AuthState {
  user: UserRecord | null
  signOut: () => void
}

const AuthContext = createContext<AuthState>({ user: null, signOut: () => {} })

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserRecord | null>(
    pb.authStore.isValid ? (pb.authStore.record as unknown as UserRecord) : null,
  )

  useEffect(() => {
    return pb.authStore.onChange(() => {
      setUser(pb.authStore.isValid ? (pb.authStore.record as unknown as UserRecord) : null)
    })
  }, [])

  const signOut = () => {
    pb.authStore.clear()
  }

  return <AuthContext.Provider value={{ user, signOut }}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  return useContext(AuthContext)
}
