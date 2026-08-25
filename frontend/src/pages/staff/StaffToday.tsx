import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { AlertTriangle, Calendar, CalendarCheck, CheckCircle2, ChevronRight, Clock, Database, FileText, KeyRound, QrCode, ShieldCheck, UserCheck, XCircle } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { staffApi, type VerifyStatsVO } from '@/api/staff'
import { isApiError } from '@/api/http'
import { todayInZone } from '@/lib/datetime'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'
import { PageHeader } from '@/components/common/PageHeader'

export default function StaffToday() {
  const today = todayInZone()
  const [timeStr, setTimeStr] = useState('')
  const [stats, setStats] = useState<VerifyStatsVO | null>(null)
  const [loading, setLoading] = useState(true)
  const [errorMsg, setErrorMsg] = useState('')
  const [requestId, setRequestId] = useState('')

  useEffect(() => {
    const updateTime = () => {
      const now = new Date()
      const pad = (value: number) => String(value).padStart(2, '0')
      setTimeStr(`${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`)
    }
    updateTime()
    const timer = setInterval(updateTime, 1000)
    return () => clearInterval(timer)
  }, [])

  const load = useCallback(() => {
    setLoading(true)
    setErrorMsg('')
    setRequestId('')
    staffApi.verifyStats().then((data) => {
      setStats(data)
      setLoading(false)
    }).catch((err) => {
      setLoading(false)
      if (isApiError(err)) {
        setErrorMsg(err.message)
        setRequestId(err.requestId)
      } else setErrorMsg('获取今日核销统计失败')
    })
  }, [])

  useEffect(() => { load() }, [load])

  return (
    <div className="space-y-6">
      <PageHeader
        title="今日核销工作台"
        description="入口现场通行核验"
        actions={(
          <div className="flex h-10 items-center gap-3 rounded-md border bg-background px-3 text-sm">
            <span className="flex items-center gap-1.5 font-medium text-foreground"><Calendar className="h-4 w-4 text-muted-foreground" />{today}</span>
            <span className="h-4 w-px bg-border" />
            <span className="flex items-center gap-1.5 font-mono tabular-nums text-muted-foreground"><Clock className="h-4 w-4" />{timeStr || '00:00:00'}</span>
          </div>
        )}
      />

      <section className="grid gap-4 lg:grid-cols-[2fr_1fr_1fr]" aria-label="核销快捷操作">
        <Card className="overflow-hidden border-0 bg-[linear-gradient(135deg,hsl(var(--primary)),#0b5f4b)] text-primary-foreground shadow-lg shadow-primary/15">
          <div className="flex h-full min-h-60 flex-col justify-between gap-7 p-6 sm:flex-row sm:items-center sm:p-8">
            <div className="space-y-3">
              <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-primary-foreground/15"><QrCode className="h-7 w-7" /></div>
              <div><p className="mb-1 text-xs font-semibold tracking-[0.16em] text-primary-foreground/65">现场主操作</p><h2 className="text-2xl font-semibold">扫描入园凭证</h2><p className="mt-2 max-w-md text-sm leading-6 text-primary-foreground/80">扫码枪录入后回车即可核销，成功、重复和无效凭证都会给出明确通行结果。</p></div>
            </div>
            <Button asChild variant="secondary" className="h-14 shrink-0 px-7 text-base font-semibold"><Link to="/staff/verify?tab=scan"><QrCode className="mr-2 h-5 w-5" />开始扫码核销</Link></Button>
          </div>
        </Card>
        <QuickAction to="/staff/verify?tab=manual" icon={<FileText className="h-6 w-6" />} title="手工核销" description="核对预约号与证件末四位" action="录入核销" />
        <QuickAction to="/staff/reservations" icon={<CalendarCheck className="h-6 w-6" />} title="今日预约" description="查询当日预约与通行状态" action="查看清单" />
      </section>

      <section className="space-y-3" aria-labelledby="today-stats-heading">
        <div className="flex items-center justify-between"><h2 id="today-stats-heading" className="text-base font-semibold text-foreground">今日概况</h2><span className="text-xs text-muted-foreground">数据来自实时核销记录</span></div>
        {loading && <div className="grid gap-3 sm:grid-cols-3 xl:grid-cols-6">{Array.from({ length: 6 }).map((_, index) => <Card key={index} className="p-4"><Skeleton className="h-16 w-full" /></Card>)}</div>}
        {errorMsg && <ErrorState title="加载核销统计失败" message={errorMsg} requestId={requestId} onRetry={load} />}
        {!loading && !errorMsg && stats && <div className="grid gap-3 sm:grid-cols-3 xl:grid-cols-6">
          <StatCard label="待入园" value={stats.confirmed} icon={<UserCheck className="h-4 w-4" />} tone="warning" />
          <StatCard label="已核销" value={stats.verified} icon={<CheckCircle2 className="h-4 w-4" />} tone="success" />
          <StatCard label="已取消" value={stats.cancelled} icon={<XCircle className="h-4 w-4" />} tone="neutral" />
          <StatCard label="已过期" value={stats.expired} icon={<XCircle className="h-4 w-4" />} tone="neutral" />
          <StatCard label="今日成功" value={stats.successToday} icon={<CheckCircle2 className="h-4 w-4" />} tone="success" />
          <StatCard label="核销尝试" value={stats.attemptsToday} icon={<AlertTriangle className="h-4 w-4" />} tone="warning" />
        </div>}
      </section>

      <Card className="overflow-hidden">
        <details className="group">
          <summary className="flex cursor-pointer list-none items-center justify-between gap-4 p-5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary"><ShieldCheck className="h-5 w-5" /></div>
              <div><h2 className="font-semibold text-foreground">核销链路如何保证一次通行</h2><p className="mt-0.5 text-sm text-muted-foreground">展开查看扫码与手工核销共用的真实校验步骤</p></div>
            </div>
            <ChevronRight className="h-5 w-5 shrink-0 text-muted-foreground transition-transform group-open:rotate-90" />
          </summary>
          <div className="border-t bg-muted/20 p-5 sm:p-6">
            <div className="grid gap-3 md:grid-cols-5">
              <FlowStep icon={<QrCode className="h-4 w-4" />} index="01" title="读取凭证" detail="保留原始 QR 载荷" />
              <FlowStep icon={<KeyRound className="h-4 w-4" />} index="02" title="验签与有效期" detail="校验密钥版本及签名" />
              <FlowStep icon={<CalendarCheck className="h-4 w-4" />} index="03" title="查询预约" detail="确认场次与预约状态" />
              <FlowStep icon={<Database className="h-4 w-4" />} index="04" title="CAS 状态迁移" detail="只允许首次核销成功" />
              <FlowStep icon={<FileText className="h-4 w-4" />} index="05" title="记录审计" detail="保留操作人与时间" />
            </div>
            <p className="mt-4 text-xs leading-5 text-muted-foreground">手工核销跳过 QR 验签，但必须核对预约编号与证件末四位，并写入 MANUAL_VERIFY 审计记录。统计区仅展示接口返回的真实聚合数据，不推算失败原因。</p>
          </div>
        </details>
      </Card>
    </div>
  )
}

function QuickAction({ to, icon, title, description, action }: { to: string; icon: ReactNode; title: string; description: string; action: string }) {
  return <Card className="flex min-h-56 flex-col justify-between p-5"><div className="space-y-3"><div className="flex h-11 w-11 items-center justify-center rounded-md bg-muted text-foreground">{icon}</div><div><h2 className="font-semibold text-foreground">{title}</h2><p className="mt-1 text-sm text-muted-foreground">{description}</p></div></div><Button asChild variant="outline" className="mt-5 h-12 w-full font-semibold"><Link to={to}>{action}</Link></Button></Card>
}

function StatCard({ label, value, icon, tone }: { label: string; value: string; icon: ReactNode; tone: 'warning' | 'success' | 'neutral' }) {
  const toneClass = { warning: 'bg-amber-100 text-amber-800', success: 'bg-emerald-100 text-emerald-800', neutral: 'bg-muted text-muted-foreground' }[tone]
  return <Card className="p-4"><div className="flex items-center justify-between gap-2"><span className="text-sm text-muted-foreground">{label}</span><span className={`flex h-8 w-8 items-center justify-center rounded-md ${toneClass}`}>{icon}</span></div><div className="mt-3 font-mono text-2xl font-semibold tabular-nums text-foreground">{value}</div></Card>
}

function FlowStep({ icon, index, title, detail }: { icon: ReactNode; index: string; title: string; detail: string }) {
  return <div className="rounded-lg border bg-background p-4"><div className="flex items-center justify-between text-primary"><span>{icon}</span><span className="font-mono text-xs text-muted-foreground">{index}</span></div><h3 className="mt-5 text-sm font-semibold text-foreground">{title}</h3><p className="mt-1 text-xs leading-5 text-muted-foreground">{detail}</p></div>
}
