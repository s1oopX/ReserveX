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
    <Card className="overflow-hidden rounded-2xl border-slate-200 bg-white shadow-[0_18px_45px_rgba(18,59,67,0.09)]">
      <CardHeader className="space-y-2 border-b border-slate-100 pb-6 pt-8">
        <div className="flex items-center gap-2">
          <KeyRound className="h-5 w-5 text-emerald-700" />
          <CardTitle className="font-serif text-3xl font-semibold text-[#123b43]">修改密码</CardTitle>
        </div>
        <CardDescription className="text-sm">
          {isForced ? '首次登录需要先设置新密码，完成后即可继续使用预约系统' : '更新密码后，现有登录会话将结束，需要重新登录'}
        </CardDescription>
      </CardHeader>
      <CardContent className="px-6 pb-7 pt-7 sm:px-8">
        {isForced && (
          <Alert variant="warning" className="mb-5">
            <AlertTriangle className="h-4 w-4" />
            <AlertTitle className="font-semibold text-xs">首次登录强制改密</AlertTitle>
            <AlertDescription className="text-xs">
              为了防范初始密码泄漏风险，系统要求必须完成密码修改后方可正常访问系统功能。
            </AlertDescription>
          </Alert>
        )}

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="cp-old">原密码 / 初始密码</Label>
            <div className="relative">
              <Lock className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
              <Input
                id="cp-old"
                type="password"
                required
                autoComplete="current-password"
                placeholder="请输入当前密码"
                value={oldPassword}
                onChange={(e) => setOldPassword(e.target.value)}
                className="h-12 rounded-xl border-slate-200 bg-slate-50/60 pl-10"
              />
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="cp-new">新密码</Label>
            <div className="relative">
              <Lock className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
              <Input
                id="cp-new"
                type="password"
                required
                autoComplete="new-password"
                minLength={8}
                placeholder="至少 8 位新密码"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                className="h-12 rounded-xl border-slate-200 bg-slate-50/60 pl-10"
              />
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="cp-confirm">确认新密码</Label>
            <div className="relative">
              <Lock className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
              <Input
                id="cp-confirm"
                type="password"
                required
                autoComplete="new-password"
                minLength={8}
                placeholder="再次输入新密码"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                className="h-12 rounded-xl border-slate-200 bg-slate-50/60 pl-10"
              />
            </div>
          </div>

          {msg && (
            <Alert variant="destructive">
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
              className="h-12 flex-1 rounded-xl font-semibold"
            >
              {busy ? '正在更新密码…' : '更新密码并重新登录'}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  )
}
