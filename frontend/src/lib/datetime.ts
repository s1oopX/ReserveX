/**
 * 时间处理 —— 全前端**唯一**允许构造 Date 对象的地方(08 §7.2 的前端一侧)。
 */

export function parseServerDateTime(s: string): number {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})$/.exec(s)
  if (!m) return NaN
  const [, y, mo, d, h, mi, se] = m
  return new Date(+y, +mo - 1, +d, +h, +mi, +se).getTime()
}

export function isPast(serverDateTime: string): boolean {
  const t = parseServerDateTime(serverDateTime)
  return Number.isFinite(t) && t < Date.now()
}

export function todayInZone(): string {
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`
}

export function nowInZone(): string {
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} ${p(now.getHours())}:${p(now.getMinutes())}:${p(now.getSeconds())}`
}

export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number)
  const value = new Date(y, m - 1, d + days)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${value.getFullYear()}-${p(value.getMonth() + 1)}-${p(value.getDate())}`
}

export function addDaysInZone(date: string, days: number): string {
  return addDays(date, days)
}

export function formatEpochSeconds(unixSeconds: number): string {
  const d = new Date(unixSeconds * 1000)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function secondsUntil(unixSeconds: number): number {
  return Math.max(0, unixSeconds - Math.floor(Date.now() / 1000))
}
