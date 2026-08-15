import { http } from './http'

/** 验证码视图 (D4) */
export interface CaptchaVO {
  key: string
  imageBase64: string // "data:image/png;base64,..."
}

export const captchaApi = {
  get: () => http.get<CaptchaVO>('/captcha'),
  verify: (key: string, input: string) =>
    http.post<boolean>('/captcha/verify', { key, input }),
}
