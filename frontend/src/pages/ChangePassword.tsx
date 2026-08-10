import { useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { authApi } from '@/api/auth'
import { isApiError } from '@/api/http'

export default function ChangePassword() {
  const nav = useNavigate()
  const location = useLocation()
  const stateToken = (location.state as { onceToken?: unknown } | null)?.onceToken
  const onceToken = typeof stateToken === 'string' ? stateToken : ''
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [msg, setMsg] = useState('')
  const [busy, setBusy] = useState(false)

  if (!onceToken) {
    return <Navigate to="/login" replace />
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (newPassword !== confirm) {
      setMsg('两次输入的新密码不一致')
      return
    }
    setBusy(true)
    setMsg('')
    try {
      await authApi.changePassword(oldPassword, newPassword, onceToken)
      nav('/login', { replace: true })
    } catch (err) {
      if (!isApiError(err)) throw err
      setMsg(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="mx-auto max-w-sm p-6">
      <h1 className="text-xl font-semibold">首次登录修改密码</h1>
      <form onSubmit={submit} className="mt-4 space-y-3">
        <label className="block">
          <span className="text-sm">初始密码</span>
          <input
            type="password"
            required
            autoComplete="current-password"
            value={oldPassword}
            onChange={(e) => setOldPassword(e.target.value)}
            className="mt-1 w-full rounded border px-3 py-2"
          />
        </label>
        <label className="block">
          <span className="text-sm">新密码</span>
          <input
            type="password"
            required
            minLength={8}
            autoComplete="new-password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            className="mt-1 w-full rounded border px-3 py-2"
          />
        </label>
        <label className="block">
          <span className="text-sm">再次输入新密码</span>
          <input
            type="password"
            required
            minLength={8}
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            className="mt-1 w-full rounded border px-3 py-2"
          />
        </label>
        {msg && <p role="alert" className="text-sm text-red-700">{msg}</p>}
        <button
          type="submit"
          disabled={busy}
          className="w-full rounded bg-emerald-600 px-4 py-2 text-white disabled:bg-gray-300"
        >
          {busy ? '提交中…' : '修改密码'}
        </button>
      </form>
    </main>
  )
}
