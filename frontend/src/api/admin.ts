import { http, type Id } from './http'

/** 管理端接口(07 §3·补·3 / 07 §四) */

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
}

export interface ReconcileItem {
  id: Id
  taskType: string
  period: string
  slotId: Id
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

export interface DashboardVO {
  todaySlots: number
  todayReservations: number
  todayVerified: number
  reconcileDiffCount: number
  stuckCount: number
}

export const adminApi = {
  // ---- 场次模板(03 §4.0 / §9.1)----------------------------------------
  /**
   * 模板 CRUD。
   * ⚠️ 改模板**不影响已生成的场次**(copy-not-reference,03 §9.1):slot 是把模板值
   *    拷进去的,不是引用。所以改了模板,明天生成的场次按新值,今天已放的不变。
   * ⚠️ 删除只允许 enabled=0(停用);物理删除会让历史 slot.template_id 悬空。
   */
  listTemplates: () => http.get<SlotTemplate[]>('/admin/slot-templates'),
  createTemplate: (tpl: Omit<SlotTemplate, 'templateId' | 'version'>) =>
    http.post<SlotTemplate>('/admin/slot-templates', tpl),
  updateTemplate: (id: Id, tpl: Partial<SlotTemplate>) =>
    http.put<SlotTemplate>(`/admin/slot-templates/${id}`, tpl),

  // ---- 场次(slot)--------------------------------------------------------
  listSlots: (date: string) => http.get<SlotDetail[]>(`/admin/slots?date=${date}`),
  increaseCapacity: (slotId: Id, delta: number, version: number) =>
    http.post<null>(`/admin/slots/${slotId}/capacity`, { delta, version }),

  // ---- 发布监控(07 §4.2)------------------------------------------------
  releaseMonitor: () => http.get<unknown>('/admin/release-monitor'),

  // ---- 对账中心 3 Tab(07 §4.1)------------------------------------------
  reconcileDiff: () => http.get<ReconcileItem[]>('/admin/reconcile/diff'),
  reconcileStuck: () => http.get<StuckItem[]>('/admin/reconcile/stuck'),
  reconcileDlq: () => http.get<unknown[]>('/admin/reconcile/dlq'),
  /**
   * 卡单/死信动作(修复/重投/回滚/忽略)。
   * ⚠️ 必须写 audit_log,前端要在请求前弹二次确认。
   */
  reconcileAction: (type: 'diff' | 'stuck' | 'dlq', id: Id, action: string) =>
    http.post<null>(`/admin/reconcile/${type}/action`, { id, action }),

  // ---- 数据驾驶舱 + 容量水位(08 §6.4)-----------------------------------
  dashboard: () => http.get<DashboardVO>('/admin/dashboard'),

  // ---- STAFF 管理(01 §一)----------------------------------------------
  /**
   * ⚠️ role 由**服务端**写死,不接受前端传 —— 任何能设 role 的 HTTP 端点
   *    本身就是提权漏洞(07 §3·补·3 ⚠️ / 红线 #28)。
   */
  listStaff: () => http.get<unknown[]>('/admin/staff'),
  createStaff: (email: string, password: string) =>
    http.post<null>('/admin/staff', { email, password }),
}
