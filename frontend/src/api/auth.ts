import { http, setAccessToken, clearSession, type Id } from './http'

export interface LoginResult {
  accessToken: string
  refreshToken: string
  userId: Id
  role: 'USER' | 'STAFF' | 'ADMIN'
}

export interface RegisterReq {
  email: string
  phone: string
  password: string
  idCard: string
}

const REFRESH_KEY = 'reservex.refresh'
const ROLE_KEY = 'reservex.role'

function notifyAuthChange() {
  window.dispatchEvent(new Event('reservex-auth-change'))
}

function remember(r: LoginResult): void {
  setAccessToken(r.accessToken)
  sessionStorage.setItem(REFRESH_KEY, r.refreshToken)
  sessionStorage.setItem(ROLE_KEY, r.role)
  notifyAuthChange()
}

export const authApi = {
  login: async (email: string, password: string) => {
    const r = await http.post<LoginResult>('/auth/login', { email, password })
    remember(r)
    return r
  },

  register: (req: RegisterReq) => http.post<null>('/auth/register', req),

  refresh: async (refreshToken: string) => {
    const r = await http.post<LoginResult>('/auth/refresh', { refreshToken })
    remember(r)
    return r
  },

  logout: async () => {
    const refreshToken = sessionStorage.getItem(REFRESH_KEY)
    try {
      await http.post<null>('/auth/logout', refreshToken ? { refreshToken } : undefined)
    } catch {
      // Ignore network error on logout
    } finally {
      clearSession()
      notifyAuthChange()
    }
  },

  changePassword: async (oldPassword: string, newPassword: string, onceToken?: string) => {
    await http.post<null>('/auth/password', { oldPassword, newPassword, onceToken })
    clearSession()
    notifyAuthChange()
  },

  currentRole: (): 'USER' | 'STAFF' | 'ADMIN' | null =>
    sessionStorage.getItem(ROLE_KEY) as 'USER' | 'STAFF' | 'ADMIN' | null,
}
