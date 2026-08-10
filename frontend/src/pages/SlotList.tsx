import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { reservationApi, type SlotVO } from '@/api/reservation'
import { Code } from '@/api/codes'
import { isApiError } from '@/api/http'
import { isPast, todayInZone } from '@/lib/datetime'

/**
 * 场次列表 / 抢号入口(07 §2.3 + §3.2)。
 *
 * 卡片状态由 slot:meta 七字段派生,四态互斥:
 *   未放号(倒计时) / 可约(显示余量) / 已满(禁用) / 已结束(禁用)
 */
export default function SlotList() {
  const nav = useNavigate()
  const [date] = useState(todayInZone)
  const [slots, setSlots] = useState<SlotVO[]>([])
  const [msg, setMsg] = useState('')
  const [grabbing, setGrabbing] = useState<string | null>(null)

  useEffect(() => {
    reservationApi
      .listSlots(date)
      .then(setSlots)
      .catch((e) => setMsg(isApiError(e) ? e.message : '加载失败'))
  }, [date])

  async function grab(slotId: string) {
    setGrabbing(slotId)
    setMsg('')
    try {
      const r = await reservationApi.grab(slotId)
      // rno 全程当字符串,直接拼进 URL,不经 Number()
      nav(`/reservation/${r.reservationNo}/qr`)
    } catch (e) {
      if (!isApiError(e)) throw e
      switch (e.code) {
        case Code.QUOTA_USED:
          setMsg('您今天已有预约')
          nav('/mine')
          break
        case Code.CAPTCHA_REQUIRED:
          // v1 简化:提示后由用户重试触发验证码弹层(07 §2.3)
          setMsg('请完成验证码后重试')
          break
        case Code.SLOT_FULL:
          setMsg('名额已满')
          break
        case Code.SERVICE_DEGRADED:
          // ⚠️ 与 SLOT_FULL 分开:降级时说"已满"是撒谎,且会让故障在用户反馈里隐形
          setMsg('系统繁忙,请稍后重试')
          break
        default:
          setMsg(e.message)
      }
    } finally {
      setGrabbing(null)
    }
  }

  return (
    <main className="mx-auto max-w-3xl p-6">
      <h1 className="text-xl font-semibold">今日场次 · {date}</h1>
      {msg && (
        <p role="status" className="mt-3 rounded bg-amber-50 px-3 py-2 text-sm text-amber-800">
          {msg}
        </p>
      )}
      <ul className="mt-4 space-y-3">
        {slots.map((s) => {
          const ended = isPast(s.validUntil)
          const disabled = !s.released || s.full || ended || grabbing === s.slotId
          return (
            <li key={s.slotId} className="flex items-center justify-between rounded border p-4">
              <div>
                <p className="font-medium">
                  {String(s.slotHour).padStart(2, '0')}:00 起 · {s.durationMin} 分钟
                </p>
                <p className="text-sm text-gray-500">
                  {!s.released
                    ? '尚未放号'
                    : ended
                      ? '预约已结束'
                      : s.full
                        ? '名额已满'
                        : `剩余 ${s.remain} 个名额`}
                </p>
              </div>
              <button
                type="button"
                disabled={disabled}
                onClick={() => grab(s.slotId)}
                className="rounded bg-emerald-600 px-4 py-2 text-white disabled:bg-gray-300"
              >
                {grabbing === s.slotId ? '提交中…' : '预约'}
              </button>
            </li>
          )
        })}
      </ul>
    </main>
  )
}
