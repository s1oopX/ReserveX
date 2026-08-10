import { useCallback, useEffect, useState } from 'react'
import { adminApi, type ReconcileItem, type StuckItem } from '@/api/admin'
import { isApiError } from '@/api/http'

type Tab = 'diff' | 'stuck' | 'dlq'

/**
 * 对账中心 三 Tab(07 §4.1 / 06 §十一类对账)。
 *
 * ⚠️ 每个动作(修复 / 重投 / 回滚 / 忽略)都会改真实库存或预约状态,
 *    所以:① 必须二次确认;② 后端必须写 audit_log。
 *    "只是个内部页面"是最危险的想法 —— 这三个 Tab 的按钮比任何用户端接口都重。
 *
 * ⚠️ diff 非零**不代表一定有 bug**:对账在读 Redis 与 DB 两个时点之间,
 *    正常抢号也会造成瞬时差。判据是**同一 slot 连续多个周期 diff 不收敛**。
 *    把每次非零都当故障处理,会淹没真正的异常。
 */
export default function AdminReconcile() {
  const [tab, setTab] = useState<Tab>('diff')
  const [diff, setDiff] = useState<ReconcileItem[]>([])
  const [stuck, setStuck] = useState<StuckItem[]>([])
  const [dlq, setDlq] = useState<unknown[]>([])
  const [msg, setMsg] = useState('')

  const load = useCallback(
    (t: Tab) => {
      setMsg('')
      const p =
        t === 'diff'
          ? adminApi.reconcileDiff().then(setDiff)
          : t === 'stuck'
            ? adminApi.reconcileStuck().then(setStuck)
            : adminApi.reconcileDlq().then(setDlq)
      p.catch((e) => setMsg(isApiError(e) ? e.message : '加载失败'))
    },
    [],
  )

  useEffect(() => load(tab), [tab, load])

  async function act(type: Tab, id: string, action: string, label: string) {
    if (!window.confirm(`确定对 ${id} 执行「${label}」?该操作会写入审计日志。`)) return
    try {
      await adminApi.reconcileAction(type, id, action)
      load(type)
    } catch (e) {
      setMsg(isApiError(e) ? e.message : '操作失败')
    }
  }

  return (
    <main className="mx-auto max-w-5xl p-6">
      <h1 className="text-xl font-semibold">对账中心</h1>
      <nav className="mt-4 flex gap-2" role="tablist">
        {(
          [
            ['diff', '库存差异'],
            ['stuck', '卡单'],
            ['dlq', '死信'],
          ] as const
        ).map(([k, label]) => (
          <button
            key={k}
            type="button"
            role="tab"
            aria-selected={tab === k}
            onClick={() => setTab(k)}
            className={`rounded border px-4 py-2 text-sm ${tab === k ? 'bg-gray-100 font-medium' : ''}`}
          >
            {label}
          </button>
        ))}
      </nav>

      {msg && (
        <p role="alert" className="mt-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          {msg}
        </p>
      )}

      {tab === 'diff' && (
        <>
          <p className="mt-3 text-sm text-gray-500">
            单次 diff 非零可能只是读取时点差异,应关注同一场次连续多周期不收敛的记录。
          </p>
          <ul className="mt-3 space-y-2 text-sm">
            {diff.map((d) => (
              <li key={d.id} className="flex items-center justify-between rounded border p-3">
                <span>
                  {d.taskType} · 场次 {d.slotId} · 差异 {d.diff} · {d.createAt}
                </span>
                <button
                  type="button"
                  onClick={() => act('diff', d.id, 'repair', '修复')}
                  className="rounded border px-3 py-1"
                >
                  修复
                </button>
              </li>
            ))}
            {diff.length === 0 && <li className="text-gray-500">无差异记录</li>}
          </ul>
        </>
      )}

      {tab === 'stuck' && (
        <ul className="mt-3 space-y-2 text-sm">
          {stuck.map((s) => (
            <li key={s.reservationNo} className="rounded border p-3">
              <div className="flex items-center justify-between">
                <span>
                  {s.reservationNo} · 场次 {s.slotId} · 重投 {s.reinjectCount} 次
                </span>
                <span className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => act('stuck', s.reservationNo, 'reinject', '重投')}
                    className="rounded border px-3 py-1"
                  >
                    重投
                  </button>
                  <button
                    type="button"
                    onClick={() => act('stuck', s.reservationNo, 'rollback', '回滚')}
                    className="rounded border px-3 py-1 text-red-600"
                  >
                    回滚
                  </button>
                </span>
              </div>
              {s.lastError && (
                <p className="mt-2 break-all font-mono text-xs text-gray-500">{s.lastError}</p>
              )}
            </li>
          ))}
          {stuck.length === 0 && <li className="text-gray-500">无卡单</li>}
        </ul>
      )}

      {tab === 'dlq' && (
        <pre className="mt-3 overflow-auto rounded border p-3 text-xs">
          {JSON.stringify(dlq, null, 2)}
        </pre>
      )}
    </main>
  )
}
