import { http, type Id } from './http'

export interface SlotTemplate {
  templateId: Id
  slotHour: number
  durationMin: number
  capacity: number
  bucketCount: number
  releaseOffsetMin: number
  enabled: boolean
  version: number
}

export interface SlotDetail {
  slotId: Id
  templateId: Id | null
  slotDate: string
  slotHour: number
  capacity: number
  bucketCount: number
  released: boolean
  releaseAt: string
  version: number
  remain?: number
  metaPresent?: boolean
}

export interface ReleaseMonitorItem {
  slotId: Id
  slotDate: string
  slotHour: number
  released: boolean
  capacity: number
  bucketCount: number
  version: number
  metaComplete: boolean
  bucketPresent: number
  bucketExpected: number
  redisRemain: number
  releaseAt: string
}

export interface StaffAccount {
  userId: Id
  email: string
  phone: string
  idCardMasked: string
  status: number
  createAt: string
}

export interface DlqView {
  items: unknown[]
  source: string
  reason: string
  stuckCount: string
}

export interface ReconcileItem {
  id: Id
  taskType: string
  period: string
  slotId: Id
  redisOccupied?: number
  dbOccupied?: number
  reservationCnt?: number
  diff: number
  createAt: string
}

export interface StuckItem {
  reservationNo: Id
  slotId: Id
  status: number
  reinjectCount: number
  lastError: string | null
  createAt: string
}

/** 管理端预约视图(比用户视图多 userId,管理端需溯源用户) */
export interface AdminReservationVO {
  reservationNo: Id
  userId: Id
  slotId: Id
  slotDate: string
  status: string
  version: number
  createAt: string
  verifyTime: string | null
  idCardMasked: string | null
}

export interface DashboardVO {
  todaySlots: number           // Java int -> JSON number
  todayReservations: string    // Java long -> JSON string
  todayVerified: string        // Java long -> JSON string
  reconcileDiffCount: string   // Java long -> JSON string
  stuckCount: string           // Java long -> JSON string
}

export const adminApi = {
  listTemplates: () => http.get<SlotTemplate[]>('/admin/slot-templates'),
  createTemplate: (tpl: Omit<SlotTemplate, 'templateId' | 'version'>) =>
    http.post<SlotTemplate>('/admin/slot-templates', tpl),
  updateTemplate: (id: Id, tpl: Partial<SlotTemplate>) =>
    http.put<SlotTemplate>(`/admin/slot-templates/${id}`, tpl),

  listSlots: (date: string) => http.get<SlotDetail[]>(`/admin/slots?date=${date}`),
  increaseCapacity: (slotId: Id, delta: number, version: number) =>
    http.post<null>(`/admin/slots/${slotId}/capacity`, { delta, version }),

  releaseMonitor: () => http.get<ReleaseMonitorItem[]>('/admin/release-monitor'),

  reconcileDiff: () => http.get<ReconcileItem[]>('/admin/reconcile/diff'),
  reconcileLatest: () => http.get<ReconcileItem[]>('/admin/reconcile/latest'),
  reconcileStuck: () => http.get<StuckItem[]>('/admin/reconcile/stuck'),
  reconcileDlq: () => http.get<DlqView>('/admin/reconcile/dlq'),
  reconcileAction: (type: 'diff' | 'stuck' | 'dlq', id: Id, action: string) =>
    http.post<number>(`/admin/reconcile/${type}/action`, { id, action }),

  dashboard: () => http.get<DashboardVO>('/admin/dashboard'),

  /** 管理员全园预约查询(广播两库归并 + 脱敏)。参数皆可选 */
  listReservations: (params: { rno?: string; slotDate?: string; status?: string }) => {
    const qs = new URLSearchParams()
    if (params.rno) qs.set('rno', params.rno)
    if (params.slotDate) qs.set('slotDate', params.slotDate)
    if (params.status) qs.set('status', params.status)
    const query = qs.toString()
    return http.get<AdminReservationVO[]>(`/admin/reservations${query ? '?' + query : ''}`)
  },

  listStaff: () => http.get<StaffAccount[]>('/admin/staff'),
  createStaff: (email: string, password: string, phone: string, idCard: string) =>
    http.post<null>('/admin/staff', { email, password, phone, idCard }),
}
