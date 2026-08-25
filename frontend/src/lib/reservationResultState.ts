import type { ReservationVO } from '@/api/reservation'

type ReservationStatus = ReservationVO['status']

export interface ReservationResultState {
  title: string
  description: string
  statusLabel: string
  tone: 'success' | 'processing' | 'warning' | 'danger' | 'neutral'
  canShowQr: boolean
  pending: boolean
  terminalFailure: boolean
}

export function getReservationResultState(status: ReservationStatus): ReservationResultState {
  switch (status) {
    case 'CONFIRMED':
      return { title: '预约已确认', description: '持久化已完成，可以查看并出示动态入园凭证。', statusLabel: '已确认', tone: 'success', canShowQr: true, pending: false, terminalFailure: false }
    case 'PENDING':
      return { title: '名额已预占', description: '系统已受理本次预约，正在异步完成持久化确认。', statusLabel: '确认中', tone: 'processing', canShowQr: false, pending: true, terminalFailure: false }
    case 'REVIEW_REQUIRED':
      return { title: '预约正在人工确认', description: '系统已保留预约编号，运营人员正在处理一致性异常。', statusLabel: '人工处理中', tone: 'warning', canShowQr: false, pending: false, terminalFailure: false }
    case 'VERIFIED':
      return { title: '预约已核销', description: '该预约已经完成现场入园核销。', statusLabel: '已核销', tone: 'success', canShowQr: false, pending: false, terminalFailure: false }
    case 'CANCELLED':
      return { title: '预约已取消', description: '该预约已取消，不能继续用于入园。', statusLabel: '已取消', tone: 'neutral', canShowQr: false, pending: false, terminalFailure: true }
    case 'EXPIRED':
      return { title: '预约已过期', description: '该预约已超过有效时段，不能继续用于入园。', statusLabel: '已过期', tone: 'warning', canShowQr: false, pending: false, terminalFailure: true }
    case 'FAILED':
      return { title: '预约确认失败', description: '该预约未能完成确认，请返回预约列表查看最新状态。', statusLabel: '处理失败', tone: 'danger', canShowQr: false, pending: false, terminalFailure: true }
  }
}
