import { http, type Id } from './http'
import type { ReservationVO } from './reservation'

/** 核销端接口(07 §3·补·3 / 07 §3.4.1) */

export interface VerifyResult {
  reservationNo: Id
  status: 'VERIFIED' | 'ALREADY_VERIFIED'
  /** 首次核销时间。ALREADY_VERIFIED 时必然有值(07 §3·补·2:data 回带首次信息) */
  verifyTime: string | null
  staffId: Id | null
}

export type StaffReservationVO = Omit<ReservationVO, 'idCardMasked'>

/** 今日核销统计(StaffToday 工作台指标) */
export interface VerifyStatsVO {
  confirmed: string        // Java long -> JSON string
  verified: string
  cancelled: string
  expired: string
  successToday: string
  attemptsToday: string
}

export const staffApi = {
  /**
   * 扫码核销。payload 是前端从 QR 图解析出的原始字符串,**不要解析/重排它**:
   * 签名覆盖全部字段及顺序,前端一旦修改(哪怕只是 pretty-print)验签必失败。
   */
  verifyScan: (payload: string) =>
    http.post<VerifyResult>('/staff/verifications', { method: 'QR', payload }),

  /**
   * 手工核销。需游客现场提供身份证末四位(避免工作人员从列表复制凭据)。
   * 后端会写 audit_log(action='MANUAL_VERIFY')。
   */
  verifyManual: (rno: Id, idCardLast4: string) =>
    http.post<VerifyResult>('/staff/verifications', {
      method: 'MANUAL',
      rno,
      idCardLast4,
    }),

  /** 今日工作台:本人负责场次的预约列表 */
  today: () => http.get<StaffReservationVO[]>('/staff/reservations'),

  /** 今日核销统计指标 */
  verifyStats: () => http.get<VerifyStatsVO>('/staff/verification-statistics'),
}
