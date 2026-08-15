import { http, type Id } from './http'

/** 场次卡片 (07 §2.3) */
export interface SlotVO {
  slotId: Id
  slotDate: string       // "2026-08-10"
  slotHour: number       // int -> number
  durationMin: number    // int -> number
  released: boolean
  releaseAt: string      // Java long -> JSON String (epoch seconds)
  validUntil: string     // "2026-08-10 11:00:00"
  remain: number         // int -> number
  full: boolean
}

/** 我的预约 (07 §2.2) */
export interface ReservationVO {
  reservationNo: Id
  slotId: Id
  slotDate: string
  slotHour: number
  status: 'PENDING' | 'CONFIRMED' | 'VERIFIED' | 'CANCELLED' | 'EXPIRED'
  version: number
  createAt: string
  verifyTime: string | null
  idCardMasked?: string
}

export interface GrabResult {
  reservationNo: Id
}

/** QR 载荷 (07 §3.4.1) */
export interface QrVO {
  payload: string
  exp: string            // Java long -> JSON String (epoch seconds)
}

export const reservationApi = {
  listSlots: (date: string) => http.get<SlotVO[]>(`/slots?date=${encodeURIComponent(date)}`),

  getSlot: (slotId: Id) => http.get<SlotVO>(`/slots/${slotId}`),

  grab: (slotId: Id, captchaToken?: string) =>
    http.post<GrabResult>('/reservation/grab', { slotId, captchaToken }),

  mine: () => http.get<ReservationVO[]>('/reservation/mine'),

  detail: (rno: Id) => http.get<ReservationVO>(`/reservation/${rno}`),

  qr: (rno: Id) => http.get<QrVO>(`/reservation/${rno}/qr`),

  cancel: (rno: Id) => http.post<null>(`/reservation/${rno}/cancel`),
}
