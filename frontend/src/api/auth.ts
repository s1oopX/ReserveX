import {
  http,
  setSession,
  clearSession,
  getRole,
  type Id,
} from './http'

export interface LoginResult {
  accessToken: string
  userId: Id
  role: 'USER' | 'STAFF' | 'ADMIN'
}

export interface RegisterReq {
  email: string
  emailCode: string
  phone: string
  password: string
  idCard: string
}

export interface CreatedUser {
  userId: Id
  ready: boolean
}

function remember(r: LoginResult): void {
  setSession(r)
}

export const authApi = {
  login: async (email: string, password: string) => {
    const r = await http.post<LoginResult>('/sessions', { email, password })
    remember(r)
    return r
  },

  register: (req: RegisterReq, registrationKey: string) =>
    http.post<CreatedUser>('/users', req, { 'Idempotency-Key': registrationKey }),

  registrationStatus: (registrationKey: string) =>
    http.get<{ status: 'PENDING' | 'READY' | 'STUCK' }>(`/registrations/${encodeURIComponent(registrationKey)}`),

  sendRegistrationCode: (email: string) =>
    http.post<null>('/email-verifications', { email }),

  logout: async () => {
    try {
      await http.delete<null>('/sessions/current')
    } catch {
      // Ignore network error on logout
    } finally {
      clearSession()
    }
  },

  changePassword: async (oldPassword: string, newPassword: string, onceToken?: string) => {
    await http.patch<null>('/users/me', { oldPassword, newPassword, onceToken })
    clearSession()
  },

  currentRole: getRole,
}
