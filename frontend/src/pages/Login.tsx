import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Eye, EyeOff, Lock, Mail, ArrowRight, ShieldCheck } from 'lucide-react'
import { authApi } from '@/api/auth'
import { Code } from '@/api/codes'
import { isApiError } from '@/api/http'
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { RequestIdHint } from '@/components/common/RequestIdHint'

export default function Login() {
  const nav = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [msg, setMsg] = useState('')
  const [requestId, setRequestId] = useState('')
  const [busy, setBusy] = useState(false)

  const registered = (location.state as { registered?: boolean; from?: { pathname?: string } } | null)?.registered
  const fromPath = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname

  function roleLanding(role: string): string {
    if (role === 'ADMIN') return '/admin/dashboard'
    if (role === 'STAFF') return '/staff/today'
    return '/slots'
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setMsg('')
    setRequestId('')
    try {
      const r = await authApi.login(email, password)
      // 若是被 RoleGuard 拦到登录页(带 from),且目标路由对该 role 合法,则回原页;
      // 否则按 role 跳各自首页,避免 USER 回到 staff/admin 页又被守卫踢到 /403。
      const target = fromPath && isAllowedForRole(fromPath, r.role) ? fromPath : roleLanding(r.role)
      nav(target, { replace: true })
    } catch (err) {
      if (!isApiError(err)) {
        setMsg('发生未知错误，请重试')
        return
      }
      setRequestId(err.requestId)
      if (err.code === Code.PASSWORD_CHANGE_REQUIRED) {
        const onceToken = (err.data as { onceToken?: unknown } | null)?.onceToken
        if (typeof onceToken === 'string') {
          nav('/change-password', { state: { onceToken } })
        } else {
          setMsg('强制改密凭证缺失，请重新登录')
        }
      } else if (err.code === Code.LOGIN_FAILED) {
        setMsg('邮箱或密码错误')
      } else {
        setMsg(err.message)
      }
    } finally {
      setBusy(false)
    }
  }

  /** 目标路由前缀是否对该 role 合法 —— 防止 USER 被回带到 /admin 再踢 403。 */
  function isAllowedForRole(path: string, role: string): boolean {
    if (role === 'ADMIN') return true // ADMIN 可访问 staff 与 admin 端
    if (role === 'STAFF') return path.startsWith('/staff')
    // USER:只允许用户端路由,admin/staff 一律不回带
    return !path.startsWith('/admin') && !path.startsWith('/staff')
  }

  return (
    <Card className="designer-card-elevated rounded-2xl overflow-hidden shadow-2xl border border-white/80">
      <CardHeader className="space-y-1.5 pb-4 border-b border-slate-100 pt-6 px-6 sm:px-8">
        <div className="flex items-center justify-between">
          <CardTitle className="text-2xl font-black font-sans text-slate-900 tracking-tight">账号登录</CardTitle>
          <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-emerald-800 bg-emerald-50 px-2.5 py-0.5 rounded-full border border-emerald-200/60 shadow-2xs">
            <ShieldCheck className="h-3 w-3" />
            官方实名凭证
          </span>
        </div>
        <CardDescription className="text-xs text-slate-600 font-medium">
          欢迎使用 ReserveX 湿地公园预约系统
        </CardDescription>
      </CardHeader>

      <CardContent className="pt-6 px-6 sm:px-8">
        {registered && (
          <Alert variant="success" className="mb-5 border-emerald-200 bg-emerald-50/70 text-emerald-900 rounded-xl">
            <AlertDescription className="text-xs font-medium">
              注册成功，请使用您的邮箱和密码登录。
            </AlertDescription>
          </Alert>
        )}

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="login-email" className="text-xs font-semibold text-slate-800">邮箱地址</Label>
            <div className="relative">
              <Mail className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
              <Input
                id="login-email"
                type="email"
                required
                placeholder="name@example.com"
                autoComplete="username"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="h-11 pl-10 rounded-xl border-slate-200 bg-slate-50/80 focus:bg-white focus:ring-2 focus:ring-emerald-500/20 text-sm font-medium transition-all"
              />
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="login-password" className="text-xs font-semibold text-slate-800">登录密码</Label>
            <div className="relative">
              <Lock className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
              <Input
                id="login-password"
                type={showPassword ? 'text' : 'password'}
                required
                placeholder="••••••••"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="h-11 pl-10 pr-10 rounded-xl border-slate-200 bg-slate-50/80 focus:bg-white focus:ring-2 focus:ring-emerald-500/20 text-sm font-medium transition-all"
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="absolute right-3.5 top-3.5 text-slate-400 hover:text-slate-600 transition-colors"
                aria-label={showPassword ? '隐藏密码' : '显示密码'}
              >
                {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>

          {msg && (
            <Alert variant="destructive" className="border-rose-200 bg-rose-50/70 text-rose-900 rounded-xl">
              <AlertDescription className="text-xs">
                <div>{msg}</div>
                {requestId && <RequestIdHint requestId={requestId} />}
              </AlertDescription>
            </Alert>
          )}

          <Button
            type="submit"
            disabled={busy}
            className="w-full h-11 rounded-xl bg-emerald-700 hover:bg-emerald-800 active:scale-[0.99] text-white font-bold text-sm shadow-md shadow-emerald-700/20 transition-all hover:scale-[1.01] gap-2"
          >
            {busy ? '正在登录…' : '登录系统'}
            {!busy && <ArrowRight className="h-4 w-4" />}
          </Button>
        </form>
      </CardContent>

      <CardFooter className="flex flex-col space-y-2 border-t border-slate-100/80 py-4 px-6 sm:px-8 text-center text-xs text-slate-600 bg-slate-50/40">
        <div>
          没有账号？{' '}
          <Link to="/register" className="font-bold text-emerald-800 underline-offset-4 hover:underline">
            立即注册
          </Link>
        </div>
        <div className="text-[11px] text-slate-500 font-normal">
          首次登录系统？可使用统一发放的初始凭证登录改密。
        </div>
      </CardFooter>
    </Card>
  )
}
