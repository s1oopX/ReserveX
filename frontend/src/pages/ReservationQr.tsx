import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { reservationApi, type QrVO } from '@/api/reservation'
import { Code } from '@/api/codes'
import { isApiError } from '@/api/http'
import { secondsUntil } from '@/lib/datetime'

/**
 * 入园二维码(07 §3.4.1)。
 *
 * ⚠️ rno 从 useParams 拿到就是 **string**,原样传给接口 —— 不要 Number(rno)。
 *    这里是 07 §3·补·4 那条精度悬崖最容易被踩的位置。
 *
 * ⚠️ 载荷 TTL 60s(08 §七 qr.ttl-sec),到点自动重取。
 *    不自动刷新会让用户在闸机前看到一个已过期的码,而错误提示是"二维码已过期" ——
 *    用户不知道要下拉刷新。
 *
 * ⚠️ 刚抢号成功就进本页时,消息可能还没落库(窗口期),后端会返
 *    RESERVATION_CONFIRMING —— 这不是错误,轮询重试即可(07 §2.4 预约处理中页)。
 */
export default function ReservationQr() {
  const { rno = '' } = useParams()
  const [qr, setQr] = useState<QrVO | null>(null)
  const [msg, setMsg] = useState('')
  const [confirming, setConfirming] = useState(false)

  const load = useCallback(async () => {
    try {
      setQr(await reservationApi.qr(rno))
      setConfirming(false)
      setMsg('')
    } catch (e) {
      if (!isApiError(e)) throw e
      if (e.code === Code.RESERVATION_CONFIRMING) {
        setConfirming(true)
        setMsg('预约正在确认,请稍候…')
      } else {
        setConfirming(false)
        setMsg(e.message)
      }
    }
  }, [rno])

  useEffect(() => {
    void load()
  }, [load])

  // 窗口期轮询:2s 一次直到落库
  useEffect(() => {
    if (!confirming) return
    const t = setInterval(() => void load(), 2000)
    return () => clearInterval(t)
  }, [confirming, load])

  // TTL 到点前 5s 重取,避免用户举着过期码
  useEffect(() => {
    if (!qr) return
    const ms = Math.max(1000, (secondsUntil(qr.exp) - 5) * 1000)
    const t = setTimeout(() => void load(), ms)
    return () => clearTimeout(t)
  }, [qr, load])

  return (
    <main className="mx-auto max-w-md p-6 text-center">
      <h1 className="text-xl font-semibold">入园码</h1>
      {msg && (
        <p role="status" className="mt-3 rounded bg-amber-50 px-3 py-2 text-sm text-amber-800">
          {msg}
        </p>
      )}
      {qr && (
        <>
          {/* v1 用文本载荷占位;渲染成二维码图形是页面层的事,
              载荷字符串本身必须原样传递(签名覆盖字段顺序) */}
          <pre className="mt-6 break-all rounded border p-4 text-left text-xs">{qr.payload}</pre>
          <p className="mt-3 text-sm text-gray-500">{secondsUntil(qr.exp)} 秒后自动刷新</p>
        </>
      )}
    </main>
  )
}
