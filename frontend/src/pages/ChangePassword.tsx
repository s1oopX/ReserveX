import { useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { KeyRound, Lock, AlertTriangle } from 'lucide-react'
import { authApi } from '@/api/auth'
import { getAccessToken, isApiError } from '@/api/http'
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { toast } from '@/components/ui/sonner'
import { RequestIdHint } from '@/components/common/RequestIdHint'

export default function ChangePassword() {
  const nav = useNavigate()
  const location = useLocation()
  const onceToken = (location.state as { onceToken?: string } | null)?.onceToken

  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [msg, setMsg] = useState('')
  const [requestId, setRequestId] = useState('')
  const [busy, setBusy] = useState(false)

  const isForced = Boolean(onceToken)

  if (!isForced && !getAccessToken()) {
    return <Navigate to="/login" replace />
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setMsg('')
    setRequestId('')

    if (newPassword.length < 8) {
      setMsg('新密码长度至少 8 位')
      return
    }

    if (newPassword !== confirmPassword) {
      setMsg('两次输入的密码不一致')
      return
    }

    setBusy(true)
    try {
      await authApi.changePassword(oldPassword, newPassword, onceToken)
      toast.success('密码修改成功，请使用新密码重新登录')
      nav('/login', { replace: true })
    } catch (err) {
      if (!isApiError(err)) {
        setMsg('网络连接失败，请稍后重试')
        return
      }
      setRequestId(err.requestId)
      setMsg(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className="shadow-xl border border-slate-200/80 rounded-2xl bg-card/95 backdrop-blur-xs">
      <CardHeader className="space-y-1.5 pb-4 border-b border-slate-100">
        <div className="flex items-center gap-2">
          <KeyRound className="h-5 w-5 text-emerald-700" />
          <CardTitle className="text-2xl font-bold font-serif text-slate-900 tracking-tight">修改密码</CardTitle>
        </div>
        <CardDescription className="text-xs text-slate-500">
          {isForced ? '检测到您首次登录，请先修改初始密码' : '为了您的账号安全，请定期更换密码'}
        </CardDescription>
      </CardHeader>
      <CardContent className="pt-6">
        {isForced && (
          <Alert variant="warning" className="mb-5 border-amber-200 bg-amber-50/70 text-amber-900">
            <AlertTriangle className="h-4 w-4" />
            <AlertTitle className="font-semibold text-xs">首次登录强制改密</AlertTitle>
            <AlertDescription className="text-xs">
              为了防范初始密码泄漏风险，系统要求必须完成密码修改后方可正常访问系统功能。
            </AlertDescription>
          </Alert>
        )}

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="cp-old" className="text-xs font-semibold text-slate-700">原密码 / 初始密码</Label>
            <div className="relative">
              <Lock className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
              <Input
                id="cp-old"
                type="password"
                required
                placeholder="请输入当前密码"
                value={oldPassword}
                onChange={(e) => setOldPassword(e.target.value)}
                className="h-11 pl-10 rounded-lg border-slate-200 bg-slate-50/50 focus:bg-background focus:ring-emerald-500/20 text-sm transition-all"
              />
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="cp-new" className="text-xs font-semibold text-slate-700">新密码</Label>
            <div className="relative">
              <Lock className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
              <Input
                id="cp-new"
                type="password"
                required
                minLength={8}
                placeholder="至少 8 位新密码"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                className="h-11 pl-10 rounded-lg border-slate-200 bg-slate-50/50 focus:bg-background focus:ring-emerald-500/20 text-sm transition-all"
              />
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="cp-confirm" className="text-xs font-semibold text-slate-700">确认新密码</Label>
            <div className="relative">
              <Lock className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
              <Input
                id="cp-confirm"
                type="password"
                required
                minLength={8}
                placeholder="再次输入新密码"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                className="h-11 pl-10 rounded-lg border-slate-200 bg-slate-50/50 focus:bg-background focus:ring-emerald-500/20 text-sm transition-all"
              />
            </div>
          </div>

          {msg && (
            <Alert variant="destructive" className="border-rose-200 bg-rose-50/70 text-rose-900">
              <AlertDescription className="text-xs">
                <div>{msg}</div>
                {requestId && <RequestIdHint requestId={requestId} />}
              </AlertDescription>
            </Alert>
          )}

          <div className="flex items-center gap-3 pt-2">
            {!isForced && (
              <Button type="button" variant="outline" onClick={() => nav(-1)} className="flex-1 h-11 rounded-lg">
                取消
              </Button>
            )}
            <Button
              type="submit"
              disabled={busy}
              className="flex-1 h-11 rounded-lg bg-emerald-700 hover:bg-emerald-800 text-white font-semibold shadow-sm transition-all duration-200 hover:-translate-y-0.5"
            >
              {busy ? '正在提交…' : '确认修改并重新登录'}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  )
}
