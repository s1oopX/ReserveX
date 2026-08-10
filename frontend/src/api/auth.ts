import { http, setAccessToken, type Id } from './http'

/** 认证接口(07 §3·补·3 / 08 §4.6:access 无状态 30min + refresh 存 Redis 白名单) */

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

let currentRefreshToken: string | null = null

export const authApi = {
  /**
   * 登录。后端走**两跳**(email_route 查 user_id → 按分片键查分库,03 §2.2)。
   *
   * ⚠️ 失败一律返 LOGIN_FAILED,前端不要试图区分"邮箱不存在"与"密码错" ——
   *    后端刻意合成同一个码以防用户枚举(07 §3·补·2 ⚠️)。
   *
   * ⚠️ 若返 PASSWORD_CHANGE_REQUIRED(超管首登),data 里带一次性改密 token,
   *    必须跳强制改密页;此时 accessToken 不会下发。
   */
  login: async (email: string, password: string) => {
    const r = await http.post<LoginResult>('/auth/login', { email, password })
    setAccessToken(r.accessToken)
    currentRefreshToken = r.refreshToken
    return r
  },

  register: (req: RegisterReq) => http.post<null>('/auth/register', req),

  refresh: async (refreshToken: string) => {
    const r = await http.post<LoginResult>('/auth/refresh', { refreshToken })
    setAccessToken(r.accessToken)
    currentRefreshToken = r.refreshToken
    return r
  },

  /**
   * 注销。
   * ⚠️ 只删 Redis 里的 refresh —— **已下发的 access 最长 30min 内仍然有效**
   *    (08 §4.6 已认下的降级点)。所以前端登出必须**同时清本地 token**,
   *    否则同一浏览器里那份 access 还能继续调接口。
   */
  logout: async () => {
    try {
      await http.post<null>('/auth/logout',
        currentRefreshToken ? { refreshToken: currentRefreshToken } : undefined)
    } finally {
      setAccessToken(null)
      currentRefreshToken = null
    }
  },

  changePassword: async (oldPassword: string, newPassword: string, onceToken?: string) => {
    await http.post<null>('/auth/password', { oldPassword, newPassword, onceToken })
    setAccessToken(null)
    currentRefreshToken = null
  },
}
