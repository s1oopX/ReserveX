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
      <Card className="designer-card-elevated rounded-2xl overflow-hidden shadow-2xl border border-white/80">
        <CardHeader className="space-y-1 pb-3 pt-5 px-6 border-b border-slate-100">
          <div className="flex items-center justify-between">
            <CardTitle className="text-xl font-black font-sans text-slate-900 tracking-tight">预约信息登记</CardTitle>
            <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-emerald-800 bg-emerald-50 px-2.5 py-0.5 rounded-full border border-emerald-200/60">
              <ShieldCheck className="h-3 w-3" />
              邮箱归属验证
            </span>
          </div>
          <CardDescription className="text-xs text-slate-500">
            登记预约信息；入园时仍需按现场规则核验证件
          </CardDescription>
        </CardHeader>

        <CardContent className="pt-4 px-6 pb-4">
          <form onSubmit={submit} className="space-y-3">
            {/* Row 1: Email (Full Width) */}
            <div className="space-y-1">
              <div className="flex justify-between items-center">
                <Label htmlFor="reg-email" className="text-xs font-semibold text-slate-700">电子邮箱</Label>
                <span className="text-[11px] text-slate-400">验证码用于确认邮箱归属</span>
              </div>
              <div className="relative">
                <Mail className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                <Input
                  id="reg-email"
                  type="email"
                  required
                  placeholder="name@example.com"
                  value={email}
                  onChange={(e) => {
                    setEmail(e.target.value)
                    setCodeSent(false)
                  }}
                  className="h-10 pl-9 rounded-xl border-slate-200 bg-slate-50/50 focus:bg-white text-xs font-medium transition-all"
                />
              </div>
            </div>

            <div className="space-y-1">
              <Label htmlFor="reg-email-code" className="text-xs font-semibold text-slate-700">邮箱验证码</Label>
              <div className="flex gap-2">
                <Input
                  id="reg-email-code"
                  type="text"
                  inputMode="numeric"
                  required
                  pattern="^\d{6}$"
                  maxLength={6}
                  placeholder="6 位验证码"
                  value={emailCode}
                  onChange={(e) => setEmailCode(e.target.value.replace(/\D/g, ''))}
                  className="h-10 rounded-xl border-slate-200 bg-slate-50/50 focus:bg-white text-xs font-mono"
                />
                <Button type="button" variant="outline" disabled={sendingCode || !email} onClick={sendCode}
                  className="h-10 shrink-0 rounded-xl text-xs">
                  {sendingCode ? '发送中…' : codeSent ? '重新发送' : '发送验证码'}
                </Button>
              </div>
              {codeSent && <p className="text-[11px] text-emerald-700">验证码已发送，10 分钟内有效且只能使用一次</p>}
            </div>

            {/* Row 2: Phone & ID Card (2-Column Grid) */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label htmlFor="reg-phone" className="text-xs font-semibold text-slate-700">手机号码</Label>
                <div className="relative">
                  <Phone className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                  <Input
                    id="reg-phone"
                    type="tel"
                    required
                    pattern="^1\d{10}$"
                    placeholder="11位手机号"
                    value={phone}
                    onChange={(e) => setPhone(e.target.value)}
                    className="h-10 pl-9 rounded-xl border-slate-200 bg-slate-50/50 focus:bg-white text-xs font-medium transition-all"
                  />
                </div>
              </div>

              <div className="space-y-1">
                <Label htmlFor="reg-idcard" className="text-xs font-semibold text-slate-700">身份证号</Label>
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
                    className="h-10 pl-9 rounded-xl border-slate-200 bg-slate-50/50 focus:bg-white text-xs font-mono transition-all"
                  />
                </div>
              </div>
            </div>

            {/* Row 3: Password & Confirm Password (2-Column Grid) */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label htmlFor="reg-password" className="text-xs font-semibold text-slate-700">设置密码</Label>
                <div className="relative">
                  <Lock className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                  <Input
                    id="reg-password"
                    type={showPassword ? 'text' : 'password'}
                    required
                    minLength={8}
                    placeholder="至少 8 位"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="h-10 pl-9 pr-8 rounded-xl border-slate-200 bg-slate-50/50 focus:bg-white text-xs font-medium transition-all"
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
                <Label htmlFor="reg-confirm" className="text-xs font-semibold text-slate-700">确认密码</Label>
                <div className="relative">
                  <Lock className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                  <Input
                    id="reg-confirm"
                    type={showPassword ? 'text' : 'password'}
                    required
                    minLength={8}
                    placeholder="再次输入"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    className="h-10 pl-9 rounded-xl border-slate-200 bg-slate-50/50 focus:bg-white text-xs font-medium transition-all"
                  />
                </div>
              </div>
            </div>

            {/* Protocol Trigger */}
            <div className="pt-0.5">
              <div
                onClick={() => setNoticeOpen(true)}
                className={`flex items-center gap-2 p-2.5 rounded-xl border cursor-pointer transition-all ${
                  agreed
                    ? 'border-emerald-300 bg-emerald-50/70 text-emerald-950 shadow-xs'
                    : 'border-slate-200 bg-slate-50/50 hover:bg-slate-100/70 text-slate-600'
                }`}
              >
                <input
                  id="reg-agreed-checkbox"
                  type="checkbox"
                  checked={agreed}
                  readOnly
                  tabIndex={-1}
                  className="h-4 w-4 rounded border-slate-300 text-emerald-700 pointer-events-none shrink-0"
                />
                <div className="text-xs leading-tight font-medium flex-1">
                  <span>已阅读并同意 </span>
                  <span className="text-emerald-800 underline font-bold hover:text-emerald-900">
                    《湿地公园预约须知与规则》
                  </span>
                </div>
                {agreed && (
                  <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-emerald-800 bg-emerald-100/80 px-2 py-0.5 rounded-full shrink-0">
                    <ShieldCheck className="h-3 w-3" />
                    已确认
                  </span>
                )}
              </div>
            </div>

            {msg && (
              <Alert variant="destructive" className="border-rose-200 bg-rose-50/70 text-rose-900 py-2 rounded-xl">
                <AlertDescription className="text-xs">
                  <div>{msg}</div>
                  {requestId && <RequestIdHint requestId={requestId} />}
                </AlertDescription>
              </Alert>
            )}

            <Button
              type="submit"
              disabled={busy}
              className="w-full h-10 rounded-xl bg-emerald-800 hover:bg-emerald-900 text-white font-bold text-xs shadow-sm transition-all"
            >
              {busy ? '正在提交注册…' : '立即注册账号'}
            </Button>
          </form>
        </CardContent>

        <CardFooter className="flex justify-center border-t border-slate-100 py-3 text-xs text-slate-500 bg-slate-50/50">
          已有账号？{' '}
          <Link to="/login" className="font-bold text-emerald-800 underline-offset-4 hover:underline ml-1">
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
