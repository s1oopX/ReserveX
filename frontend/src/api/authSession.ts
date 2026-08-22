export type Role = 'USER' | 'STAFF' | 'ADMIN'

export interface AuthSession {
  accessToken: string
  role: Role
}

interface SessionStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

const SESSION_KEY = 'reservex.auth'
const LEGACY_ACCESS_KEY = 'reservex.access'
const LEGACY_REFRESH_KEY = 'reservex.refresh'
const LEGACY_ROLE_KEY = 'reservex.role'

function isRole(value: unknown): value is Role {
  return value === 'USER' || value === 'STAFF' || value === 'ADMIN'
}

function isSession(value: unknown): value is AuthSession {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<AuthSession>
  return typeof candidate.accessToken === 'string' && candidate.accessToken.length > 0
    && isRole(candidate.role)
}

function removeLegacy(storage: SessionStorage): void {
  storage.removeItem(LEGACY_ACCESS_KEY)
  storage.removeItem(LEGACY_REFRESH_KEY)
  storage.removeItem(LEGACY_ROLE_KEY)
}

export function loadAuthSession(storage: SessionStorage): AuthSession | null {
  const raw = storage.getItem(SESSION_KEY)
  if (raw) {
    try {
    const parsed: unknown = JSON.parse(raw)
    if (isSession(parsed)) {
      const session = { accessToken: parsed.accessToken, role: parsed.role }
      if (raw !== JSON.stringify(session)) storage.setItem(SESSION_KEY, JSON.stringify(session))
      return session
    }
    } catch {
      // Fall through to the one-time legacy migration.
    }
    storage.removeItem(SESSION_KEY)
  }

  const legacy = {
    accessToken: storage.getItem(LEGACY_ACCESS_KEY),
    role: storage.getItem(LEGACY_ROLE_KEY),
  }
  if (isSession(legacy)) {
    storage.setItem(SESSION_KEY, JSON.stringify(legacy))
    removeLegacy(storage)
    return legacy
  }
  removeLegacy(storage)
  return null
}

export function saveAuthSession(storage: SessionStorage, session: AuthSession): void {
  storage.setItem(SESSION_KEY, JSON.stringify(session))
  removeLegacy(storage)
}

export function removeAuthSession(storage: SessionStorage): void {
  storage.removeItem(SESSION_KEY)
  removeLegacy(storage)
}
