import { useState } from 'react'
import { adminApi } from '@/api/admin'
import { isApiError } from '@/api/http'
import { Dialog, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { toast } from '@/components/ui/sonner'

const EMAIL_DOMAINS = [
  'qq.com', '163.com', '126.com', 'sina.com', 'sohu.com', 'foxmail.com',
  'outlook.com', 'hotmail.com', 'gmail.com', 'yahoo.com', '139.com', '189.cn', 'aliyun.com',
]

export function CreateStaffDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [idCard, setIdCard] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [errorMsg, setErrorMsg] = useState('')

  const validate = (): string | null => {
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return '邮箱格式不合法'
    const domain = email.split('@')[1]?.toLowerCase()
    if (!EMAIL_DOMAINS.includes(domain)) return `仅支持以下邮箱域名:${EMAIL_DOMAINS.join(', ')}`
    if (!/^1\d{10}$/.test(phone)) return '手机号格式不合法(11 位,以 1 开头)'
    if (password.length < 8) return '密码至少 8 位'
    if (!/^[1-9]\d{16}[0-9Xx]$/.test(idCard)) return '身份证号格式不合法(18 位)'
    return null
  }

  const submit = () => {
    const err = validate()
    if (err) { setErrorMsg(err); return }
    setSubmitting(true)
    setErrorMsg('')
    adminApi
      .createStaff(email, password, phone, idCard)
      .then(() => {
        toast.success('STAFF 账号创建成功', '新建成功')
        onDone()
      })
      .catch((e) => {
        setSubmitting(false)
        setErrorMsg(isApiError(e) ? e.message : '创建失败')
      })
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogHeader>
        <DialogTitle>新建 STAFF 账号</DialogTitle>
        <DialogDescription>
          创建核销人员账号。role 固定为 STAFF,无法通过此接口创建 ADMIN。
        </DialogDescription>
      </DialogHeader>
      <div className="py-4 space-y-3">
        <Field label="邮箱" value={email} onChange={setEmail} placeholder="staff@example.com" />
        <Field label="手机号" value={phone} onChange={setPhone} placeholder="11 位手机号" />
        <Field label="密码" value={password} onChange={setPassword} placeholder="至少 8 位" type="password" />
        <Field label="身份证号" value={idCard} onChange={setIdCard} placeholder="18 位身份证号" />
        {errorMsg && <p className="text-xs text-destructive">{errorMsg}</p>}
      </div>
      <DialogFooter>
        <Button variant="outline" onClick={onClose} disabled={submitting}>取消</Button>
        <Button onClick={submit} disabled={submitting}>{submitting ? '提交中...' : '确认创建'}</Button>
      </DialogFooter>
      <DialogClose onClick={onClose} />
    </Dialog>
  )
}

function Field({ label, value, onChange, placeholder, type = 'text' }: {
  label: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  type?: string
}) {
  return (
    <div className="space-y-1.5">
      <label className="text-sm font-medium text-foreground">{label}</label>
      <Input type={type} value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </div>
  )
}
