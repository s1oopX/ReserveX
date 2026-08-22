/**
 * 错误码枚举 —— 与 07 §3·补·2 错误码表**一一对位**,不新增语义。
 *
 * ⚠️ 后端返的是**字符串**枚举而非数字(07 §3·补·1):排障时日志里 `QUOTA_USED`
 *    比 `1003` 直接可读,前端 switch 也不需要对照表。
 */
export const Code = {
  OK: 'OK',

  // 场次
  SLOT_NOT_FOUND: 'SLOT_NOT_FOUND',
  SLOT_NOT_RELEASED: 'SLOT_NOT_RELEASED',
  SLOT_ENDED: 'SLOT_ENDED',
  SLOT_FULL: 'SLOT_FULL',
  SLOT_RELEASED_LOCKED: 'SLOT_RELEASED_LOCKED',
  TEMPLATE_INVALID: 'TEMPLATE_INVALID',

  // 抢号
  QUOTA_USED: 'QUOTA_USED',
  CAPTCHA_REQUIRED: 'CAPTCHA_REQUIRED',
  CAPTCHA_INVALID: 'CAPTCHA_INVALID',
  RATE_LIMITED: 'RATE_LIMITED',
  SERVICE_DEGRADED: 'SERVICE_DEGRADED',

  // 预约
  RESERVATION_NOT_FOUND: 'RESERVATION_NOT_FOUND',
  RESERVATION_CONFIRMING: 'RESERVATION_CONFIRMING',
  RESERVATION_NOT_STARTED: 'RESERVATION_NOT_STARTED',
  ALREADY_CANCELLED: 'ALREADY_CANCELLED',
  ALREADY_EXPIRED: 'ALREADY_EXPIRED',

  // 核销
  ALREADY_VERIFIED: 'ALREADY_VERIFIED',
  QR_INVALID: 'QR_INVALID',
  QR_EXPIRED: 'QR_EXPIRED',

  // 账号
  REGISTRATION_CONFLICT: 'REGISTRATION_CONFLICT',
  REGISTRATION_CODE_INVALID: 'REGISTRATION_CODE_INVALID',
  LOGIN_FAILED: 'LOGIN_FAILED',
  REFRESH_IN_PROGRESS: 'REFRESH_IN_PROGRESS',
  ACCOUNT_BANNED: 'ACCOUNT_BANNED',
  PASSWORD_CHANGE_REQUIRED: 'PASSWORD_CHANGE_REQUIRED',

  // 协议层
  BAD_REQUEST: 'BAD_REQUEST',
  PRECONDITION_REQUIRED: 'PRECONDITION_REQUIRED',
  PRECONDITION_FAILED: 'PRECONDITION_FAILED',
  STATE_CONFLICT: 'STATE_CONFLICT',
  NOT_FOUND: 'NOT_FOUND',
  FORBIDDEN: 'FORBIDDEN',
  UNAUTHORIZED: 'UNAUTHORIZED',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
} as const

export type ErrCode = (typeof Code)[keyof typeof Code]

/**
 * 默认展示文案(07 §3·补·2 第四列)。
 *
 * ⚠️ `SLOT_FULL` 与 `SERVICE_DEGRADED` 文案**必须不同**:两者用户侧都是"约不到",
 *    但语义相反 —— 前者名额真没了,后者系统不可用。
 *    降级时说"名额已满"**是撒谎**,且会让运维在用户反馈里完全看不到故障。
 *
 * ⚠️ `LOGIN_FAILED` 只有一句话:邮箱不存在 / 孤儿 route / 密码错三种原因
 *    对外必须合成同一文案,否则这个接口就是**用户枚举接口**(07 §3·补·2 ⚠️)。
 */
export const CodeText: Record<string, string> = {
  [Code.SLOT_NOT_FOUND]: '场次不存在',
  [Code.SLOT_NOT_RELEASED]: '尚未放号',
  [Code.SLOT_ENDED]: '预约已结束',
  [Code.SLOT_FULL]: '名额已满',
  [Code.SLOT_RELEASED_LOCKED]: '该场次已放号,此项不可改',
  [Code.TEMPLATE_INVALID]: '放号时点晚于场次结束,或容量小于分桶数',

  [Code.QUOTA_USED]: '您今天已有预约',
  [Code.CAPTCHA_REQUIRED]: '请先完成图形验证',
  [Code.CAPTCHA_INVALID]: '验证码错误,请重试',
  [Code.RATE_LIMITED]: '请求过于频繁,请稍后重试',
  [Code.SERVICE_DEGRADED]: '系统繁忙,请稍后重试',

  [Code.RESERVATION_NOT_FOUND]: '预约不存在',
  [Code.RESERVATION_CONFIRMING]: '预约正在确认,请稍候重试',
  [Code.RESERVATION_NOT_STARTED]: '预约场次尚未开始',
  [Code.ALREADY_CANCELLED]: '已取消,无法操作',
  [Code.ALREADY_EXPIRED]: '已过期,无法操作',

  [Code.ALREADY_VERIFIED]: '该预约已核销',
  [Code.QR_INVALID]: '无效二维码',
  [Code.QR_EXPIRED]: '二维码已过期,请刷新',

  [Code.REGISTRATION_CONFLICT]: '邮箱或手机号已注册',
  [Code.REGISTRATION_CODE_INVALID]: '邮箱验证码错误或已过期',
  [Code.LOGIN_FAILED]: '邮箱或密码错误',
  [Code.REFRESH_IN_PROGRESS]: '凭证正在刷新,请稍候重试',
  [Code.ACCOUNT_BANNED]: '账号已封禁,请联系管理员',

  [Code.BAD_REQUEST]: '请求参数不合法',
  [Code.PRECONDITION_REQUIRED]: '缺少资源版本条件,请刷新后重试',
  [Code.PRECONDITION_FAILED]: '资源版本已变化,请刷新后重试',
  [Code.STATE_CONFLICT]: '资源状态已变化,请刷新后重试',
  [Code.NOT_FOUND]: '资源不存在',
  [Code.FORBIDDEN]: '无权操作',
  [Code.UNAUTHORIZED]: '请先登录',
  [Code.INTERNAL_ERROR]: '系统异常,请稍后重试',
}
