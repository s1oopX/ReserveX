import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Mail, Phone, IdCard, Lock, Eye, EyeOff, ShieldCheck } from 'lucide-react'
import { authApi } from '@/api/auth'
import { Code } from '@/api/codes'
import { isApiError } from '@/api/http'
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { RequestIdHint } from '@/components/common/RequestIdHint'
import { NoticeDialog } from '@/components/common/NoticeDialog'

export default function Register() {
  const nav = useNavigate()
  const [email, setEmail] = useState('')
  const [emailCode, setEmailCode] = useState('')
  const [sendingCode, setSendingCode] = useState(false)
  const [codeSent, setCodeSent] = useState(false)
  const [phone, setPhone] = useState('')
  const [idCard, setIdCard] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [agreed, setAgreed] = useState<boolean>(false)
  const [noticeOpen, setNoticeOpen] = useState<boolean>(false)
  const [showPassword, setShowPassword] = useState<boolean>(false)
  const [msg, setMsg] = useState('')
  const [requestId, setRequestId] = useState('')
  const [busy, setBusy] = useState(false)
  const [registrationKey] = useState(() => crypto.randomUUID())

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setMsg('')
    setRequestId('')

    if (password.length < 8) {
      setMsg('密码必须至少 8 位字符')
      return
    }

    if (password !== confirmPassword) {
      setMsg('两次输入的密码不一致')
      return
    }

    if (!agreed) {
      setMsg('请阅读并同意预约须知')
      return
    }

    setBusy(true)
    try {
      const result = await authApi.register({ email, emailCode, phone, password, idCard }, registrationKey)
      if (result.ready) {
        nav('/login', { state: { registered: true } })
      } else {
        setMsg('注册信息已受理，账号正在完成初始化，请稍后使用邮箱登录')
      }
    } catch (err) {
      if (!isApiError(err)) {
        setMsg('网络繁忙，请稍后重试')
        return
      }
      setRequestId(err.requestId)
      if (err.code === Code.REGISTRATION_CONFLICT) {
        setMsg('邮箱或手机号已被注册')
      } else {
        setMsg(err.message)
      }
    } finally {
      setBusy(false)
    }
  }

  async function sendCode() {
    setMsg('')
    setRequestId('')
    if (!email || !email.includes('@')) {
      setMsg('请先填写有效邮箱')
      return
    }
    setSendingCode(true)
    try {
      await authApi.sendRegistrationCode(email)
      setCodeSent(true)
    } catch (err) {
      if (isApiError(err)) {
        setRequestId(err.requestId)
        setMsg(err.message)
      } else {
        setMsg('网络繁忙，请稍后重试')
      }
    } finally {
      setSendingCode(false)
    }
  }

  return (
    <>
      <Card className="overflow-hidden rounded-2xl border-slate-200 bg-white shadow-[0_18px_45px_rgba(18,59,67,0.09)]">
        <CardHeader className="space-y-2 border-b border-slate-100 px-6 pb-6 pt-8">
          <div className="flex items-center justify-between">
            <CardTitle className="font-serif text-3xl font-semibold text-[#123b43]">创建预约账户</CardTitle>
            <span className="inline-flex items-center gap-1 rounded-full border border-primary/20 bg-primary/5 px-2.5 py-1 text-[11px] font-medium text-primary">
              <ShieldCheck className="h-3 w-3" />
              邮箱归属验证
            </span>
          </div>
          <CardDescription className="text-sm">
            完成邮箱与实名信息验证后，即可查看和预约开放时段
          </CardDescription>
        </CardHeader>

        <CardContent className="px-6 pb-6 pt-6">
          <form onSubmit={submit} className="space-y-4">
            {/* Row 1: Email (Full Width) */}
            <div className="space-y-1">
              <div className="flex justify-between items-center">
              <Label htmlFor="reg-email">电子邮箱</Label>
                <span className="text-[11px] text-slate-400">验证码用于确认邮箱归属</span>
              </div>
              <div className="relative">
                <Mail className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                <Input
                  id="reg-email"
                  type="email"
                  required
                  autoComplete="email"
                  placeholder="name@example.com"
                  value={email}
                  onChange={(e) => {
                    setEmail(e.target.value)
                    setCodeSent(false)
                  }}
                  className="h-11 rounded-xl border-slate-200 bg-slate-50/60 pl-9"
                />
              </div>
            </div>

            <div className="space-y-1">
              <Label htmlFor="reg-email-code">邮箱验证码</Label>
              <div className="flex gap-2">
                <Input
                  id="reg-email-code"
                  type="text"
                  inputMode="numeric"
                  required
                  autoComplete="one-time-code"
                  pattern="^\d{6}$"
                  maxLength={6}
                  placeholder="6 位验证码"
                  value={emailCode}
                  onChange={(e) => setEmailCode(e.target.value.replace(/\D/g, ''))}
                  className="h-11 rounded-xl border-slate-200 bg-slate-50/60 font-mono"
                />
                <Button type="button" variant="outline" disabled={sendingCode || !email} onClick={sendCode}
                  className="h-10 shrink-0 text-xs">
                  {sendingCode ? '发送中…' : codeSent ? '重新发送' : '发送验证码'}
                </Button>
              </div>
              {codeSent && <p className="text-[11px] text-emerald-700">验证码已发送，10 分钟内有效且只能使用一次</p>}
            </div>

            {/* Row 2: Phone & ID Card (2-Column Grid) */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label htmlFor="reg-phone">手机号码</Label>
                <div className="relative">
                  <Phone className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                  <Input
                    id="reg-phone"
                    type="tel"
                    required
                    autoComplete="tel"
                    pattern="^1\d{10}$"
                    placeholder="11位手机号"
                    value={phone}
                    onChange={(e) => setPhone(e.target.value)}
                  className="h-11 rounded-xl border-slate-200 bg-slate-50/60 pl-9"
                  />
                </div>
              </div>

              <div className="space-y-1">
                <Label htmlFor="reg-idcard">身份证号</Label>
                <div className="relative">
                  <IdCard className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                  <Input
                    id="reg-idcard"
                    type="text"
                    required
                    pattern="^[1-9]\d{16}[0-9Xx]$"
                    placeholder="18位身份证号"
                    value={idCard}
                    onChange={(e) => setIdCard(e.target.value.toUpperCase())}
                    className="h-11 rounded-xl border-slate-200 bg-slate-50/60 pl-9 font-mono"
                  />
                </div>
              </div>
            </div>

            {/* Row 3: Password & Confirm Password (2-Column Grid) */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label htmlFor="reg-password">设置密码</Label>
                <div className="relative">
                  <Lock className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                  <Input
                    id="reg-password"
                    type={showPassword ? 'text' : 'password'}
                    required
                    autoComplete="new-password"
                    minLength={8}
                    placeholder="至少 8 位"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="h-11 rounded-xl border-slate-200 bg-slate-50/60 pl-9 pr-8"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="absolute right-2.5 top-3 text-slate-400 hover:text-slate-600 transition-colors"
                    aria-label={showPassword ? '隐藏密码' : '显示密码'}
                  >
                    {showPassword ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                  </button>
                </div>
              </div>

              <div className="space-y-1">
                <Label htmlFor="reg-confirm">确认密码</Label>
                <div className="relative">
                  <Lock className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                  <Input
                    id="reg-confirm"
                    type={showPassword ? 'text' : 'password'}
                    required
                    autoComplete="new-password"
                    minLength={8}
                    placeholder="再次输入"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    className="h-11 rounded-xl border-slate-200 bg-slate-50/60 pl-9"
                  />
                </div>
              </div>
            </div>

            {/* Protocol Trigger */}
            <div className="pt-0.5">
              <button
                type="button"
                role="checkbox"
                aria-checked={agreed}
                onClick={() => setNoticeOpen(true)}
                className={`flex w-full cursor-pointer items-center gap-2 rounded-xl border p-3 text-left ${
                  agreed
                    ? 'border-primary/30 bg-primary/5 text-foreground'
                    : 'border-input bg-muted/20 text-muted-foreground hover:bg-muted/40'
                }`}
              >
                <span aria-hidden="true" className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border ${agreed ? 'border-primary bg-primary' : 'border-slate-300 bg-white'}`}>
                  {agreed && <span className="h-1.5 w-1.5 rounded-sm bg-white" />}
                </span>
                <div className="text-xs leading-tight font-medium flex-1">
                  <span>我已阅读并同意 </span>
                  <span className="font-medium text-primary underline hover:text-primary/80">
                    《湿地公园预约须知与规则》
                  </span>
                </div>
                {agreed && (
                    <span className="inline-flex shrink-0 items-center gap-1 rounded-md bg-primary/10 px-2 py-0.5 text-[11px] font-medium text-primary">
                    <ShieldCheck className="h-3 w-3" />
                    已确认
                  </span>
                )}
              </button>
            </div>

            {msg && (
              <Alert variant="destructive" className="py-2">
                <AlertDescription className="text-xs">
                  <div>{msg}</div>
                  {requestId && <RequestIdHint requestId={requestId} />}
                </AlertDescription>
              </Alert>
            )}

            <Button
              type="submit"
              disabled={busy}
              className="h-12 w-full rounded-xl text-sm font-semibold"
            >
              {busy ? '正在创建账户…' : '创建预约账户'}
            </Button>
          </form>
        </CardContent>

        <CardFooter className="flex justify-center border-t border-slate-100 bg-slate-50/60 py-5 text-sm text-muted-foreground">
          已有账号？{' '}
          <Link to="/login" className="ml-1 font-medium text-primary underline-offset-4 hover:underline">
            返回登录
          </Link>
        </CardFooter>
      </Card>

      <NoticeDialog
        open={noticeOpen}
        onDecline={() => setNoticeOpen(false)}
        onAccept={() => {
          setAgreed(true)
          setNoticeOpen(false)
          if (msg === '请阅读并同意预约须知') {
            setMsg('')
          }
        }}
      />
    </>
  )
}
