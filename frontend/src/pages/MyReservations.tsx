import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { reservationApi, type ReservationVO } from '@/api/reservation'
import { isApiError } from '@/api/http'

/**
 * 我的预约(07 §2.2)。
 *
 * ⚠️ 列表由 **DB 行 + occupy 载荷**合并而来,窗口期(消息还没落库)的那一笔
 *    在 DB 里根本不存在 —— 后端从 occupy 里读 cancelled/expired 标记派生状态返回。
 *    **前端看不到 PENDING 这个词**(M8:不向用户暴露窗口期),
 *    它只是一条状态为「已预约」的普通记录。
 *
 * ⚠️ 取消返 OK + CANCELLED 而不是"处理中"(07 §2.1.1)。M1 已定:**取消不返还配额**,
 *    弹窗文案必须说清 —— 用户以为能改约而取消,是最容易的投诉来源。
 */

const StatusText: Record<ReservationVO['status'], string> = {
  PENDING: '已预约',      // 窗口期也显示"已预约",与 CONFIRMED 对用户无差别
  CONFIRMED: '已预约',
  VERIFIED: '已入园',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
}

export default function MyReservations() {
  const [list, setList] = useState<ReservationVO[]>([])
  const [msg, setMsg] = useState('')

  const load = useCallback(() => {
    reservationApi
      .mine()
      .then(setList)
      .catch((e) => setMsg(isApiError(e) ? e.message : '加载失败'))
  }, [])

  useEffect(load, [load])

  async function cancel(rno: string) {
    // M1:取消不返还今日配额 —— 必须在确认前说清楚
    if (!window.confirm('取消后今天将无法再次预约,确定取消吗?')) return
    try {
      await reservationApi.cancel(rno)
      setMsg('已取消')
      load()
    } catch (e) {
      setMsg(isApiError(e) ? e.message : '取消失败')
      load()
    }
  }

  return (
    <main className="mx-auto max-w-3xl p-6">
      <h1 className="text-xl font-semibold">我的预约</h1>
      {msg && (
        <p role="status" className="mt-3 rounded bg-amber-50 px-3 py-2 text-sm text-amber-800">
          {msg}
        </p>
      )}
      <ul className="mt-4 space-y-3">
        {list.map((r) => (
          <li key={r.reservationNo} className="rounded border p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="font-medium">
                  {r.slotDate} {String(r.slotHour).padStart(2, '0')}:00
                </p>
                <p className="text-sm text-gray-500">
                  {StatusText[r.status]} · 编号 {r.reservationNo}
                </p>
              </div>
              <div className="flex gap-2">
                {(r.status === 'CONFIRMED' || r.status === 'PENDING') && (
                  <>
                    <Link
                      to={`/reservation/${r.reservationNo}/qr`}
                      className="rounded border px-3 py-2 text-sm"
                    >
                      入园码
                    </Link>
                    <button
                      type="button"
                      onClick={() => cancel(r.reservationNo)}
                      className="rounded border px-3 py-2 text-sm text-red-600"
                    >
                      取消
                    </button>
                  </>
                )}
              </div>
            </div>
          </li>
        ))}
        {list.length === 0 && <li className="text-sm text-gray-500">暂无预约</li>}
      </ul>
    </main>
  )
}
