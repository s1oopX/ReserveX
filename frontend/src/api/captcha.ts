import { http } from './http'

/** 验证码视图 (D4) */
export interface CaptchaVO {
  key: string
  imageBase64: string // "data:image/png;base64,..."
}

export const captchaApi = {
  create: () => http.post<CaptchaVO>('/captchas'),
}
