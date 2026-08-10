import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { authApi } from '@/api/auth'
import { Code } from '@/api/codes'
import { isApiError } from '@/api/http'

/**
 * 登录(07 §2.1 / 03 §2.2 两跳读路径)。
 *
 * ⚠️ 失败一律提示"邮箱或密码错误" —— 后端刻意只返 LOGIN_FAILED 一个码防枚举,
 *    前端不要自己造出更精确的文案。
 *
 * ⚠️ 超管首登返 PASSWORD_CHANGE_REQUIRED,此时**没有 accessToken**,
 *    必须跳强制改密;绕过它就等于让 .env 里那个初始口令长期有效(08 §4.1 坑四)。
 */
export default function Login() {
  const nav = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [msg, setMsg] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setMsg('')
    try {
      const r = await authApi.login(email, password)
      nav(r.role === 'ADMIN' ? '/admin/templates' : r.role === 'STAFF' ? '/staff/verify' : '/')
    } catch (err) {
      if (!isApiError(err)) throw err
      if (err.code === Code.PASSWORD_CHANGE_REQUIRED) {
        setMsg('首次登录必须修改密码')
        // v1 简化:强制改密页待补(07 §四 清单),此处先停在提示
      } else if (err.code === Code.LOGIN_FAILED) {
        setMsg('邮箱或密码错误')
      } else {
        setMsg(err.message)
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="mx-auto max-w-sm p-6">
      <h1 className="text-xl font-semibold">登录</h1>
      <form onSubmit={submit} className="mt-4 space-y-3">
        <label className="block">
          <span className="text-sm">邮箱</span>
          <input
            type="email"
            required
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="mt-1 w-full rounded border px-3 py-2"
          />
        </label>
        <label className="block">
          <span className="text-sm">密码</span>
          <input
            type="password"
            required
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="mt-1 w-full rounded border px-3 py-2"
          />
        </label>
        {msg && (
          <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-red-700">
            {msg}
          </p>
        )}
        <button
          type="submit"
          disabled={busy}
          className="w-full rounded bg-emerald-600 px-4 py-2 text-white disabled:bg-gray-300"
        >
          {busy ? '登录中…' : '登录'}
        </button>
      </form>
    </main>
  )
}
