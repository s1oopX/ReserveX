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

let accessToken: string | null = null

export function setAccessToken(t: string | null): void {
  accessToken = t
}

/**
 * ⚠️ **ID 全程当字符串**(07 §3·补·4)。
 *
 * 后端已把所有 Snowflake ID 序列化成字符串,前端这一侧的纪律是
 * **绝不把它塞进任何会经过 `Number()` 的路径** —— 包括看似无害的
 * `Number(rno)`、`parseInt(rno)`、`+rno`、`rno > 0` 这类隐式转换。
 *
 * 后果不是报错,而是**末 2~3 位被静默改写**:轻则取码时 RESERVATION_NOT_FOUND
 * (用户看到"预约成功"却取不到码),重则改写后恰好命中另一条真实 rno
 * —— 越权读到他人的码,而后端的归属校验挡不住(它校验"这个 rno 属不属于我",
 * 而攻击者不需要伪造,精度丢失自己就把 rno 改成了邻居的)。
 *
 * ⚠️ 同理 `slotDate` 不要用 `new Date("2026-08-10")` 中转:浏览器按 UTC 解析,
 *    在负时区机器上显示会差一天。演示环境不暴露,换台机器就错。直接当字符串用。
 */
export type Id = string

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  // 08 §七 sa-token.token-name: Authorization
  if (accessToken) headers['Authorization'] = accessToken

  let resp: Response
  try {
    resp = await fetch(`/api${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    // 网络层失败没有 requestId —— 这一支必须与"后端返 500"区分开,
    // 否则排障时会去后端日志里找一个根本不存在的 requestId
    throw new ApiError('NETWORK_ERROR', '网络连接失败,请检查网络', '')
  }

  // 401/403/429/500 也带统一响应包(07 §3·补·1),照常解
  let payload: ApiResult<T>
  try {
    payload = (await resp.json()) as ApiResult<T>
  } catch {
    throw new ApiError('INTERNAL_ERROR', CodeText[Code.INTERNAL_ERROR], '')
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
