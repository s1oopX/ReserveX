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
  durationMin: number
  validUntil: string
  capacity: number
  bucketCount: number
  released: boolean
  releaseAt: string
  version: number
  remain?: number
  metaPresent?: boolean
}

export type SlotResource = Omit<SlotDetail, 'remain' | 'metaPresent'>

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
  version: number
  createAt: string
}

export interface CreatedStaff {
  userId: Id
  ready: boolean
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

export interface DeadLetterItem {
  messageId: string
  sourceGroup: string
  targetTopic: string
  reconsumeTimes: number
  status: 'PENDING' | 'REPLAYING' | 'REPLAYED'
  capturedAt: string
  updateAt: string | null
  resolverId: Id | null
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

export interface AdminReservationPage {
  items: AdminReservationVO[]
  hasMore: boolean
  nextCursor: string | null
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
  updateTemplate: (id: Id, tpl: Partial<SlotTemplate> & Pick<SlotTemplate, 'version'>) => {
    const { version, ...patch } = tpl
    return http.patch<SlotTemplate>(`/admin/slot-templates/${id}`, patch,
      { 'If-Match': `"${version}"` })
  },

  listSlots: (date: string) => http.get<SlotDetail[]>(`/admin/slots?date=${encodeURIComponent(date)}`),
  increaseCapacity: (slotId: Id, capacity: number, version: number) =>
    http.patch<SlotResource>(`/admin/slots/${slotId}`, { capacity },
      { 'If-Match': `"${version}"` }),

  releaseMonitor: () => http.get<ReleaseMonitorItem[]>('/admin/release-monitor'),

  reconcileDiff: () => http.get<ReconcileItem[]>('/admin/reconciliation-logs?scope=current'),
  reconcileLatest: () => http.get<ReconcileItem[]>('/admin/reconciliation-logs?scope=latest'),
  reconcileStuck: () => http.get<StuckItem[]>('/admin/stuck-reservations'),
  reconcileAction: (_type: 'stuck', id: Id) =>
    http.patch<StuckItem>(`/admin/stuck-reservations/${id}`, {
      status: 'ROLLED_BACK',
    }).then(() => 1),
  listDeadLetters: () => http.get<DeadLetterItem[]>('/admin/dead-letter-messages'),
  replayDeadLetter: (messageId: string) =>
    http.patch<DeadLetterItem>(`/admin/dead-letter-messages/${encodeURIComponent(messageId)}`, {
      status: 'REPLAYED',
    }),

  dashboard: () => http.get<DashboardVO>('/admin/dashboard'),

  /** 管理员全园预约查询(广播两库归并 + 脱敏)。参数皆可选 */
  listReservations: (params: { rno?: string; slotDate?: string; status?: string; cursor?: string; size?: number }) => {
    const qs = new URLSearchParams()
    if (params.rno) qs.set('rno', params.rno)
    if (params.slotDate) qs.set('slotDate', params.slotDate)
    if (params.status) qs.set('status', params.status)
    if (params.cursor) qs.set('cursor', params.cursor)
    if (params.size) qs.set('size', String(params.size))
    const query = qs.toString()
    return http.get<AdminReservationPage>(`/admin/reservations${query ? '?' + query : ''}`)
  },

  listStaff: () => http.get<StaffAccount[]>('/staff-members'),
  createStaff: (email: string, password: string, phone: string, idCard: string,
                idempotencyKey: string) =>
    http.post<CreatedStaff>('/staff-members', { email, password, phone, idCard },
      { 'Idempotency-Key': idempotencyKey }),
  setStaffBanned: (userId: Id, banned: boolean, version: number) =>
    http.patch<StaffAccount>(`/staff-members/${userId}`, { banned },
      { 'If-Match': `"${version}"` }),
}
