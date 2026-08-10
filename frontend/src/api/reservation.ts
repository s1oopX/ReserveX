import { http, type Id } from './http'

/**
 * 访客端接口(07 §3·补·3)。
 *
 * ⚠️ 所有 ID 字段类型都是 {@link Id}(= string),不是 number。
 *    这不是风格选择 —— 见 http.ts 里 Id 的注释。
 */

/** 场次卡片(07 §2.3:读 slot:meta 七字段派生状态) */
export interface SlotVO {
  slotId: Id
  slotDate: string       // "2026-08-10",不经 Date 中转
  slotHour: number       // 小整数,数字无妨
  durationMin: number
  released: boolean
  releaseAt: number      // unix 秒 —— 倒计时要做算术
  validUntil: string     // "2026-08-10 11:00:00",不带时区后缀(全系统单时区)
  remain: number
  full: boolean
}

/** 我的预约(07 §2.2:DB + occupy 合并,窗口期由 cancelled/expired 标记派生) */
export interface ReservationVO {
  reservationNo: Id
  slotId: Id
  slotDate: string
  slotHour: number
  status: 'PENDING' | 'CONFIRMED' | 'VERIFIED' | 'CANCELLED' | 'EXPIRED'
  version: number
  createAt: string
  verifyTime: string | null
}

export interface GrabResult {
  reservationNo: Id
}

/** QR 载荷(07 §3.4.1)。payload 原样回传给核销端,前端不解析、不重排字段 */
export interface QrVO {
  payload: string
  exp: number            // unix 秒
}

export const reservationApi = {
  listSlots: (date: string) => http.get<SlotVO[]>(`/slots?date=${encodeURIComponent(date)}`),

  getSlot: (slotId: Id) => http.get<SlotVO>(`/slots/${slotId}`),

  /**
   * 抢号。captchaToken 仅在上一次返 CAPTCHA_REQUIRED 后才带。
   * ⚠️ 重复点击不产生第二次副作用:后端靠 dup key + rno 唯一键幂等(07 §3·补·3 ⚠️)。
   */
  grab: (slotId: Id, captchaToken?: string) =>
    http.post<GrabResult>('/reservation/grab', { slotId, captchaToken }),

  mine: () => http.get<ReservationVO[]>('/reservation/mine'),

  detail: (rno: Id) => http.get<ReservationVO>(`/reservation/${rno}`),

  /** 取码。只能取自己的,越权后端返 403 FORBIDDEN */
  qr: (rno: Id) => http.get<QrVO>(`/reservation/${rno}/qr`),

  cancel: (rno: Id) => http.post<null>(`/reservation/${rno}/cancel`),
}
