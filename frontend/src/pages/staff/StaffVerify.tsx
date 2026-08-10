import { useState } from 'react'
import { staffApi, type VerifyResult } from '@/api/staff'
import { Code } from '@/api/codes'
import { isApiError } from '@/api/http'

/**
 * 扫码核销(07 §3.4 核销闭环)。
 *
 * ⚠️ payload 从二维码解析出来后**原样提交**,不解析、不重排、不 pretty-print ——
 *    签名覆盖全部字段及其顺序,前端动一下就验签失败,而报错是"无效二维码",
 *    现场会以为是游客的码有问题。
 *
 * ⚠️ ALREADY_VERIFIED **不是失败**:它要展示首次核销时间与操作人(后端在 data 里回带)。
 *    工作人员据此判断是"游客重复扫"还是"同事已放行",这是现场唯一的判据。
 *
 * ⚠️ RESERVATION_CONFIRMING(窗口期核销:DB 查无但 occupy 在)要提示"稍候重试",
 *    **不能提示"预约不存在"** —— 那会让工作人员把有效游客拦在门外。
 */
export default function StaffVerify() {
  const [payload, setPayload] = useState('')
  const [result, setResult] = useState<VerifyResult | null>(null)
  const [msg, setMsg] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setMsg('')
    setResult(null)
    try {
      setResult(await staffApi.verifyScan(payload))
      setPayload('')
    } catch (err) {
      if (!isApiError(err)) throw err
      switch (err.code) {
        case Code.ALREADY_VERIFIED:
          // data 里带首次核销信息,当成"结果"展示而非"错误"
          setResult(err.data as VerifyResult)
          break
        case Code.RESERVATION_CONFIRMING:
          setMsg('预约正在确认,请稍候重试(不要放行)')
          break
        case Code.QR_EXPIRED:
          setMsg('二维码已过期,请游客刷新后重扫')
          break
        default:
          setMsg(err.message)
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="mx-auto max-w-lg p-6">
      <h1 className="text-xl font-semibold">扫码核销</h1>
      <form onSubmit={submit} className="mt-4 space-y-3">
        {/* v1 用文本框接收扫码枪输入;摄像头扫码是 v2(07 §四) */}
        <textarea
          required
          rows={4}
          value={payload}
          onChange={(e) => setPayload(e.target.value)}
          placeholder="扫码枪输入或粘贴二维码内容"
          className="w-full rounded border p-3 font-mono text-xs"
        />
        <button
          type="submit"
          disabled={busy}
          className="w-full rounded bg-emerald-600 px-4 py-2 text-white disabled:bg-gray-300"
        >
          {busy ? '核销中…' : '核销'}
        </button>
      </form>

      {msg && (
        <p role="alert" className="mt-4 rounded bg-amber-50 px-3 py-2 text-sm text-amber-800">
          {msg}
        </p>
      )}
      {result && (
        <div className="mt-4 rounded border p-4">
          <p className="font-medium">
            {result.status === 'VERIFIED' ? '核销成功,请放行' : '该预约此前已核销'}
          </p>
          <p className="text-sm text-gray-500">编号 {result.reservationNo}</p>
          {result.verifyTime && (
            <p className="text-sm text-gray-500">首次核销于 {result.verifyTime}</p>
          )}
        </div>
      )}
    </main>
  )
}
