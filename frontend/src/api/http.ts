import { Code, CodeText, type ErrCode } from './codes'

/** 统一响应包(07 §3·补·1)。失败时 data 为 null,唯一例外是 ALREADY_VERIFIED 回带首次核销信息。 */
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

const ACCESS_KEY = 'reservex.access'
const REFRESH_KEY = 'reservex.refresh'
const ROLE_KEY = 'reservex.role'

let accessToken: string | null = sessionStorage.getItem(ACCESS_KEY)

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(t: string | null): void {
  accessToken = t
  if (t) sessionStorage.setItem(ACCESS_KEY, t)
  else sessionStorage.removeItem(ACCESS_KEY)
}

export function getRefreshToken(): string | null {
  return sessionStorage.getItem(REFRESH_KEY)
}

export function clearSession(): void {
  accessToken = null
  sessionStorage.removeItem(ACCESS_KEY)
  sessionStorage.removeItem(REFRESH_KEY)
  sessionStorage.removeItem(ROLE_KEY)
}

export type Id = string

let refreshPromise: Promise<string | null> | null = null

async function doRefresh(): Promise<string | null> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) return null
  try {
    const resp = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
    if (!resp.ok) return null
    const payload = (await resp.json()) as ApiResult<{ accessToken: string; refreshToken: string; role: string }>
    if (payload.code === Code.OK && payload.data?.accessToken) {
      setAccessToken(payload.data.accessToken)
      sessionStorage.setItem(REFRESH_KEY, payload.data.refreshToken)
      if (payload.data.role) sessionStorage.setItem(ROLE_KEY, payload.data.role)
      return payload.data.accessToken
    }
    return null
  } catch {
    return null
  }
}

async function request<T>(method: string, path: string, body?: unknown, isRetry = false): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (accessToken) headers['Authorization'] = accessToken

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

  let payload: ApiResult<T>
  try {
    payload = (await resp.json()) as ApiResult<T>
  } catch {
    throw new ApiError('INTERNAL_ERROR', CodeText[Code.INTERNAL_ERROR], '')
  }

  // 检查 401 / UNAUTHORIZED
  const isAuthPath = path.startsWith('/auth/login') || path.startsWith('/auth/register') || path.startsWith('/auth/refresh')
  const isUnauthorized = resp.status === 401 || payload.code === Code.UNAUTHORIZED

  if (isUnauthorized && !isAuthPath && !isRetry) {
    if (!refreshPromise) {
      refreshPromise = doRefresh().finally(() => {
        refreshPromise = null
      })
    }
    const newTok = await refreshPromise
    if (newTok) {
      return request<T>(method, path, body, true)
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
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
}
