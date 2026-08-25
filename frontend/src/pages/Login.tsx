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
      <Card className="overflow-hidden rounded-2xl border-slate-200 bg-white shadow-[0_18px_45px_rgba(18,59,67,0.09)]">
      <CardHeader className="space-y-2 border-b border-slate-100 px-6 pb-6 pt-8 sm:px-8">
        <div className="flex items-center justify-between">
            <CardTitle className="font-serif text-3xl font-semibold text-[#123b43]">欢迎回来</CardTitle>
            <span className="inline-flex items-center gap-1 rounded-full border border-primary/20 bg-primary/5 px-2.5 py-1 text-[11px] font-medium text-primary">
            <ShieldCheck className="h-3 w-3" />
            实名信息保护
          </span>
        </div>
        <CardDescription className="text-sm">
          登录后查看场次、提交预约并管理动态入园凭证
        </CardDescription>
      </CardHeader>

      <CardContent className="px-6 pb-7 pt-7 sm:px-8">
        {registered && (
          <Alert variant="success" className="mb-5">
            <AlertDescription className="text-sm">
              注册成功，请使用您的邮箱和密码登录。
            </AlertDescription>
          </Alert>
        )}

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="login-email">邮箱地址</Label>
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
                className="h-12 rounded-xl border-slate-200 bg-slate-50/60 pl-10"
              />
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="login-password">登录密码</Label>
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
                className="h-12 rounded-xl border-slate-200 bg-slate-50/60 pl-10 pr-10"
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="absolute right-3.5 top-3.5 rounded-md p-1 text-slate-400 transition-colors hover:text-slate-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                aria-label={showPassword ? '隐藏密码' : '显示密码'}
              >
                {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>

          {msg && (
            <Alert variant="destructive">
              <AlertDescription className="text-sm">
                <div>{msg}</div>
                {requestId && <RequestIdHint requestId={requestId} />}
              </AlertDescription>
            </Alert>
          )}

          <Button
            type="submit"
            disabled={busy}
            className="h-12 w-full gap-2 rounded-xl text-sm font-semibold"
          >
            {busy ? '正在登录…' : '登录并查看场次'}
            {!busy && <ArrowRight className="h-4 w-4" />}
          </Button>
        </form>
      </CardContent>

      <CardFooter className="flex flex-col space-y-2 border-t border-slate-100 bg-slate-50/60 px-6 py-5 text-center text-sm text-muted-foreground sm:px-8">
        <div>
          没有账号？{' '}
          <Link to="/register" className="font-medium text-primary underline-offset-4 hover:underline">
            立即注册
          </Link>
        </div>
        <div className="text-xs">
          首次登录系统？可使用统一发放的初始凭证登录改密。
        </div>
      </CardFooter>
    </Card>
  )
}
