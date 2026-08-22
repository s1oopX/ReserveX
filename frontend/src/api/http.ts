import { Code, CodeText, type ErrCode } from './codes'
import {
  loadAuthSession,
  removeAuthSession,
  saveAuthSession,
  type AuthSession,
  type Role,
} from './authSession'

/** 统一响应包(07 §3·补·1)。部分业务失败会在 data 中携带后续流程所需信息。 */
export interface ApiResult<T> {
  code: ErrCode | string
  msg: string
  data: T | null
  requestId: string
}

/**
  * 业务失败以异常形式抛出,把 code 一起带上 —— 调用方 `catch (e) { if (isApiError(e) …) }`
  * 就能按 code 分支,而不必在每个调用点解一遍响应包。
  */
export class ApiError extends Error {
  constructor(
    readonly code: string,
    msg: string,
    readonly requestId: string,
    readonly data: unknown = null,
  ) {
    super(msg || CodeText[code] || '请求失败')
    this.name = 'ApiError'
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError
}

let authSession = loadAuthSession(sessionStorage)
let authRevision = 0

function notifyAuthChange(): void {
  window.dispatchEvent(new Event('reservex-auth-change'))
}

export function getAccessToken(): string | null {
  return authSession?.accessToken ?? null
}

export function getRole(): Role | null {
  return authSession?.role ?? null
}

export function setSession(session: AuthSession): void {
  saveAuthSession(sessionStorage, session)
  authSession = session
  authRevision += 1
  notifyAuthChange()
}

export function clearSession(): void {
  authSession = null
  authRevision += 1
  removeAuthSession(sessionStorage)
  notifyAuthChange()
}

export type Id = string

let refreshPromise: Promise<string | null> | null = null

async function doRefresh(): Promise<string | null> {
  const sourceSession = authSession
  const sourceRevision = authRevision
  if (!sourceSession) return null
  const { accessToken: oldAccessToken } = sourceSession

  const deadline = Date.now() + 20_000
  while (Date.now() < deadline) {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(),
      Math.min(8_000, deadline - Date.now()))
    try {
      const resp = await fetch('/api/sessions/current', {
        method: 'PATCH',
        headers: {
          Accept: 'application/json',
          Authorization: `Bearer ${oldAccessToken}`,
        },
        credentials: 'same-origin',
        signal: controller.signal,
      })
      const payload = (await resp.json()) as ApiResult<{
        accessToken: string
        role: string
      }>
      if (payload.code === Code.OK && payload.data?.accessToken) {
        const nextSession: AuthSession = {
          accessToken: payload.data.accessToken,
          role: payload.data.role as Role,
        }
        if (authRevision !== sourceRevision || authSession !== sourceSession) {
          void revokePair(nextSession)
          return null
        }
        setSession(nextSession)
        return payload.data.accessToken
      }
      if (payload.code !== Code.REFRESH_IN_PROGRESS && resp.status < 500) return null
    } catch {
      // A timeout or broken response is ambiguous; retry the same old token pair.
    } finally {
      window.clearTimeout(timeout)
    }
    const remaining = deadline - Date.now()
    if (remaining > 0) {
      await new Promise(resolve => window.setTimeout(resolve, Math.min(250, remaining)))
    }
  }
  return null
}

async function revokePair(session: AuthSession): Promise<void> {
  try {
    await fetch('/api/sessions/current', {
      method: 'DELETE',
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${session.accessToken}`,
      },
      credentials: 'same-origin',
      keepalive: true,
    })
  } catch {
    // Best effort: the original logout also revokes a concurrently rotating pair server-side.
  }
}

async function request<T>(method: string, path: string, body?: unknown, isRetry = false,
                          extraHeaders: Record<string, string> = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extraHeaders }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const sentAccessToken = getAccessToken()
  if (sentAccessToken) headers['Authorization'] = `Bearer ${sentAccessToken}`

  let resp: Response
  try {
    resp = await fetch(`/api${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError('NETWORK_ERROR', '网络连接失败,请检查网络', '')
  }

  if (resp.status === 204) return undefined as T

  let payload: ApiResult<T>
  try {
    payload = (await resp.json()) as ApiResult<T>
  } catch {
    throw new ApiError('INTERNAL_ERROR', CodeText[Code.INTERNAL_ERROR], '')
  }

  // 检查 401 / UNAUTHORIZED
  const isAuthPath = path === '/users' || path.startsWith('/email-verifications')
    || path === '/sessions' || path.startsWith('/sessions/current')
  const isUnauthorized = resp.status === 401 || payload.code === Code.UNAUTHORIZED

  if (isUnauthorized && !isAuthPath) {
    const currentAccessToken = getAccessToken()
    if (!isRetry && currentAccessToken && currentAccessToken !== sentAccessToken) {
      return request<T>(method, path, body, true, extraHeaders)
    }
    if (isRetry) {
      clearSession()
      window.location.href = '/login'
      throw new ApiError(Code.UNAUTHORIZED, CodeText[Code.UNAUTHORIZED], payload.requestId || '')
    }
    if (!refreshPromise) {
      refreshPromise = doRefresh().finally(() => {
        refreshPromise = null
      })
    }
    const newTok = await refreshPromise
    if (newTok) {
      return request<T>(method, path, body, true, extraHeaders)
    } else {
      clearSession()
      window.location.href = '/login'
      throw new ApiError(Code.UNAUTHORIZED, CodeText[Code.UNAUTHORIZED], payload.requestId || '')
    }
  }

  if (payload.code !== Code.OK) {
    throw new ApiError(payload.code, payload.msg, payload.requestId, payload.data)
  }
  return payload.data as T
}

export const http = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) =>
    request<T>('POST', path, body, false, headers),
  patch: <T>(path: string, body?: unknown, headers?: Record<string, string>) =>
    request<T>('PATCH', path, body, false, headers),
  delete: <T>(path: string, body?: unknown) => request<T>('DELETE', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
}
