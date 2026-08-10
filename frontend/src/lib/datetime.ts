/**
 * 时间处理 —— 全前端**唯一**允许构造 Date 对象的地方(08 §7.2 的前端一侧)。
 *
 * ⚠️ 后端返的 DATETIME 是 `"2026-08-10 11:00:00"`,**不带时区后缀**(07 §3·补·4):
 *    全系统单一时区(Asia/Shanghai),带偏移量反而给前端制造换算机会。
 *
 * ⚠️ 直接 `new Date("2026-08-10 11:00:00")` 是**不可移植的**:
 *    这个格式不在 ES 规范里,各浏览器解析行为不同(Safari 历史上返 Invalid Date)。
 *    而 `new Date("2026-08-10")`(纯日期)更糟 —— 规范要求按 **UTC** 解析,
 *    在负时区机器上 `toLocaleDateString()` 会显示成前一天。
 *    演示环境(+08:00)永远不暴露这个问题,换台机器就错 —— 典型的"永远绿"缺陷。
 *
 * 所以:后端时刻字符串只在本文件里转成 Date,且**只用于比较大小**,
 * 展示一律直接用原字符串切片。
 */

/** 后端 DATETIME 字符串 → 毫秒时间戳。跨浏览器安全:显式拆字段,不依赖 Date 的字符串解析。 */
export function parseServerDateTime(s: string): number {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})$/.exec(s)
  if (!m) return NaN
  const [, y, mo, d, h, mi, se] = m
  // 本地时区构造:浏览器与服务端约定同为 Asia/Shanghai。
  // 用 Date.UTC 反而会引入一次错误的偏移。
  return new Date(+y, +mo - 1, +d, +h, +mi, +se).getTime()
}

/** 该时刻是否已过。用于"预约已结束"这类判据。 */
export function isPast(serverDateTime: string): boolean {
  const t = parseServerDateTime(serverDateTime)
  return Number.isFinite(t) && t < Date.now()
}

/** 今天(本地时区)的 `YYYY-MM-DD`。不经 toISOString —— 那个转 UTC,零点前后会差一天。 */
export function todayInZone(): string {
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`
}

/** 剩余秒数(用于放号倒计时)。exp 是 unix **秒**(07 §3·补·4)。 */
export function secondsUntil(unixSeconds: number): number {
  return Math.max(0, unixSeconds - Math.floor(Date.now() / 1000))
}
