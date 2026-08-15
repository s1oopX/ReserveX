import React, { createContext, useContext, useState, useEffect, useCallback } from 'react'
import { authApi } from '@/api/auth'

interface AuthContextType {
  role: 'USER' | 'STAFF' | 'ADMIN' | null
  logout: () => Promise<void>
  refreshRole: () => void
}

const AuthContext = createContext<AuthContextType | undefined>(undefined)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [role, setRole] = useState<'USER' | 'STAFF' | 'ADMIN' | null>(() => authApi.currentRole())

  const refreshRole = useCallback(() => {
    setRole(authApi.currentRole())
  }, [])

  useEffect(() => {
    const handleAuthChange = () => {
      refreshRole()
    }
    window.addEventListener('reservex-auth-change', handleAuthChange)
    window.addEventListener('storage', handleAuthChange)
    return () => {
      window.removeEventListener('reservex-auth-change', handleAuthChange)
      window.removeEventListener('storage', handleAuthChange)
    }
  }, [refreshRole])

  const logout = async () => {
    await authApi.logout()
    refreshRole()
  }

  return (
    <AuthContext.Provider value={{ role, logout, refreshRole }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
