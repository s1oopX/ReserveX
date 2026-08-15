import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { QrCode, FileText, Calendar, Clock, UserCheck, CheckCircle2, XCircle, AlertTriangle } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { staffApi, type VerifyStatsVO } from '@/api/staff'
import { isApiError } from '@/api/http'
import { todayInZone } from '@/lib/datetime'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'

export default function StaffToday() {
  const nav = useNavigate()
  const today = todayInZone()
  const [timeStr, setTimeStr] = useState<string>('')
  const [stats, setStats] = useState<VerifyStatsVO | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')

  useEffect(() => {
    const updateTime = () => {
      const now = new Date()
      const p = (n: number) => String(n).padStart(2, '0')
      setTimeStr(`${p(now.getHours())}:${p(now.getMinutes())}:${p(now.getSeconds())}`)
    }
    updateTime()
    const timer = setInterval(updateTime, 1000)
    return () => clearInterval(timer)
  }, [])

  const load = useCallback(() => {
    setLoading(true)
    setErrorMsg('')
    setRequestId('')
    staffApi
      .verifyStats()
      .then((data) => {
        setStats(data)
        setLoading(false)
      })
      .catch((err) => {
        setLoading(false)
        if (isApiError(err)) {
          setErrorMsg(err.message)
          setRequestId(err.requestId)
        } else {
          setErrorMsg('获取今日核销统计失败')
        }
      })
  }, [])

  useEffect(() => {
    load()
  }, [load])

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between border-b pb-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-foreground font-serif">
            今日核销工作台
          </h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            湿地公园入口核销管理系统 · 平板高效作业模式
          </p>
        </div>
        <div className="flex items-center gap-4 text-sm bg-card border px-4 py-2 rounded-lg shadow-2xs font-mono">
          <div className="flex items-center gap-1.5 text-primary font-semibold">
            <Calendar className="h-4 w-4" />
            <span>{today}</span>
          </div>
          <div className="flex items-center gap-1.5 text-muted-foreground">
            <Clock className="h-4 w-4" />
            <span>{timeStr || '00:00:00'}</span>
          </div>
        </div>
      </div>

      <div className="grid gap-6 sm:grid-cols-3">
        <Card
          onClick={() => nav('/staff/verify?tab=scan')}
          className="cursor-pointer hover:border-primary transition-all shadow-sm hover:shadow-md border-2"
        >
          <CardContent className="p-6 flex flex-col items-center text-center space-y-3">
            <div className="h-14 w-14 rounded-full bg-primary/10 text-primary flex items-center justify-center">
              <QrCode className="h-8 w-8" />
            </div>
            <h2 className="text-lg font-bold text-foreground">扫码核销</h2>
            <p className="text-xs text-muted-foreground">
              聚焦扫码枪快速扫描游客动态 QR 码自动提交核销
            </p>
            <Button className="w-full min-h-[44px] font-semibold mt-2">
              进入扫码模式
            </Button>
          </CardContent>
        </Card>

        <Card
          onClick={() => nav('/staff/verify?tab=manual')}
          className="cursor-pointer hover:border-primary transition-all shadow-sm hover:shadow-md border"
        >
          <CardContent className="p-6 flex flex-col items-center text-center space-y-3">
            <div className="h-14 w-14 rounded-full bg-teal-500/10 text-teal-700 flex items-center justify-center">
              <FileText className="h-8 w-8" />
            </div>
            <h2 className="text-lg font-bold text-foreground">手工核销</h2>
            <p className="text-xs text-muted-foreground">
              二次确认脱敏身份证件号进行人工手工补录核销
            </p>
            <Button variant="outline" className="w-full min-h-[44px] font-semibold mt-2">
              进入手工核销
            </Button>
          </CardContent>
        </Card>

        <Card
          onClick={() => nav('/staff/reservations')}
          className="cursor-pointer hover:border-primary transition-all shadow-sm hover:shadow-md border"
        >
          <CardContent className="p-6 flex flex-col items-center text-center space-y-3">
            <div className="h-14 w-14 rounded-full bg-amber-500/10 text-amber-700 flex items-center justify-center">
              <UserCheck className="h-8 w-8" />
            </div>
            <h2 className="text-lg font-bold text-foreground">今日预约列表</h2>
            <p className="text-xs text-muted-foreground">
              查询当日常次预约清单与实时核销通行状态
            </p>
            <Button variant="outline" className="w-full min-h-[44px] font-semibold mt-2">
              查看今日预约
            </Button>
          </CardContent>
        </Card>
      </div>

      {/* 今日核销统计指标(接 GET /staff/verify-stats) */}
      <div>
        <h2 className="text-base font-bold text-foreground mb-3">今日核销统计</h2>
        {loading && (
          <div className="grid gap-4 sm:grid-cols-3 lg:grid-cols-6">
            {Array.from({ length: 6 }).map((_, i) => (
              <Card key={i} className="p-4"><Skeleton className="h-16 w-full" /></Card>
            ))}
          </div>
        )}
        {errorMsg && (
          <ErrorState title="加载核销统计失败" message={errorMsg} requestId={requestId} onRetry={load} />
        )}
        {!loading && !errorMsg && stats && (
          <div className="grid gap-4 sm:grid-cols-3 lg:grid-cols-6">
            <StatCard label="待入园" value={stats.confirmed} icon={<UserCheck className="h-4 w-4" />} tone="amber" />
            <StatCard label="已核销" value={stats.verified} icon={<CheckCircle2 className="h-4 w-4" />} tone="emerald" />
            <StatCard label="已取消" value={stats.cancelled} icon={<XCircle className="h-4 w-4" />} tone="slate" />
            <StatCard label="已过期" value={stats.expired} icon={<XCircle className="h-4 w-4" />} tone="slate" />
            <StatCard label="今日核销成功" value={stats.successToday} icon={<CheckCircle2 className="h-4 w-4" />} tone="emerald" />
            <StatCard label="核销尝试流水" value={stats.attemptsToday} icon={<AlertTriangle className="h-4 w-4" />} tone="amber" />
          </div>
        )}
      </div>
    </div>
  )
}

function StatCard({ label, value, icon, tone }: {
  label: string
  value: string
  icon: React.ReactNode
  tone: 'amber' | 'emerald' | 'slate'
}) {
  const toneCls = {
    amber: 'text-amber-700 bg-amber-500/10',
    emerald: 'text-emerald-700 bg-emerald-500/10',
    slate: 'text-slate-600 bg-slate-500/10',
  }[tone]
  return (
    <Card className="p-4 shadow-2xs border">
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs text-muted-foreground">{label}</span>
        <div className={`h-7 w-7 rounded-full flex items-center justify-center ${toneCls}`}>{icon}</div>
      </div>
      <div className="text-2xl font-bold font-mono text-foreground">{value}</div>
    </Card>
  )
}
