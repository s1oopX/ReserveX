import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'

interface RoleGuardProps {
  allowedRoles: ('USER' | 'STAFF' | 'ADMIN')[]
  children: React.ReactNode
}

export function RoleGuard({ allowedRoles, children }: RoleGuardProps) {
  const location = useLocation()
  const { role } = useAuth()

  if (!role) {
    return <Navigate to="/login" state={{ from: location }} replace />
  }

  if (!allowedRoles.includes(role)) {
    if (role === 'ADMIN' && allowedRoles.includes('STAFF')) {
      return <>{children}</>
    }
    return <Navigate to="/403" replace />
  }

  return <>{children}</>
}
