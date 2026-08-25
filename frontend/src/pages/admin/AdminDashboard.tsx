import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import { Calendar, TicketCheck, CheckCircle, AlertTriangle, Database, ArrowUpRight, ArrowRight } from 'lucide-react'
import { adminApi, type DashboardVO } from '@/api/admin'
import { isApiError } from '@/api/http'
import { Card, CardContent } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'
import { PageHeader } from '@/components/common/PageHeader'

export default function AdminDashboard() {
  const [data, setData] = useState<DashboardVO | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')

  const loadDashboard = () => {
    setLoading(true)
    setErrorMsg('')
    setRequestId('')
    adminApi
      .dashboard()
      .then((res) => {
        setData(res)
        setLoading(false)
      })
      .catch((err) => {
        setLoading(false)
        if (isApiError(err)) {
          setErrorMsg(err.message)
          setRequestId(err.requestId)
        } else {
          setErrorMsg('获取数据驾驶舱看板失败')
        }
      })
  }

  useEffect(() => {
    loadDashboard()
  }, [])

  if (loading) {
    return (
      <div className="space-y-6">
        <PageHeader title="运行概览" description="正在加载今日业务与一致性状态" />
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
          {[1, 2, 3, 4, 5].map((i) => (
            <Card key={i} className="p-4">
              <Skeleton className="h-4 w-20 mb-2" />
              <Skeleton className="h-8 w-16" />
            </Card>
          ))}
        </div>
        <Card className="p-6">
          <Skeleton className="h-20 w-full" />
        </Card>
      </div>
    )
  }

  if (errorMsg) {
    return (
      <div className="space-y-6">
        <PageHeader title="运行概览" description="今日业务与一致性状态" />
        <ErrorState
          title="运行概览加载失败"
          message={errorMsg}
          requestId={requestId}
          onRetry={loadDashboard}
        />
      </div>
    )
  }

  if (!data) return null

  const todayResNum = Number(data.todayReservations) || 0
  const todayVerNum = Number(data.todayVerified) || 0
  const diffNum = Number(data.reconcileDiffCount) || 0
  const stuckNum = Number(data.stuckCount) || 0

  const verifyRatio = todayResNum > 0 ? Math.round((todayVerNum / todayResNum) * 100) : 0
  const hasExceptions = diffNum > 0 || stuckNum > 0

  return (
    <div className="space-y-6">
      <PageHeader
        title="运行概览"
        description="先处理一致性异常，再查看今日业务量"
      />

      <Card className={hasExceptions ? 'border-amber-300 bg-amber-50/70' : 'border-emerald-200 bg-emerald-50/60'}>
        <CardContent className="flex flex-col gap-3 p-5 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-start gap-3">
            {hasExceptions ? <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-700" /> : <CheckCircle className="mt-0.5 h-5 w-5 shrink-0 text-emerald-700" />}
            <div>
              <p className="font-semibold text-foreground">{hasExceptions ? '存在需要人工关注的运行异常' : '当前没有待处置异常'}</p>
              <p className="mt-1 text-xs text-muted-foreground">
                {hasExceptions ? `差异 ${diffNum} 条，卡单 ${stuckNum} 笔；先进入对账中心确认收敛状态。` : '数据来自当前管理端接口；未接入的基础设施指标不会在此虚构。'}
              </p>
            </div>
          </div>
          <Link to="/admin/reconcile" className="inline-flex shrink-0 items-center gap-1.5 text-sm font-semibold text-primary hover:underline">
            查看处置中心 <ArrowRight className="h-4 w-4" />
          </Link>
        </CardContent>
      </Card>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <Card className="shadow-2xs">
          <CardContent className="p-5 pt-5 flex items-center justify-between">
            <div>
              <p className="text-xs text-muted-foreground font-medium">今日场次数</p>
              <h3 className="text-2xl font-bold text-foreground mt-1 font-mono">{data.todaySlots}</h3>
            </div>
            <div className="h-9 w-9 rounded-md bg-primary/10 text-primary flex items-center justify-center">
              <Calendar className="h-5 w-5" />
            </div>
          </CardContent>
        </Card>

        <Card className="shadow-2xs">
          <CardContent className="p-5 pt-5 flex items-center justify-between">
            <div>
              <p className="text-xs text-muted-foreground font-medium">今日预约数</p>
              <h3 className="text-2xl font-bold text-foreground mt-1 font-mono">{data.todayReservations}</h3>
            </div>
            <div className="h-9 w-9 rounded-md bg-success/15 text-success-foreground flex items-center justify-center">
              <TicketCheck className="h-5 w-5" />
            </div>
          </CardContent>
        </Card>

        <Card className="shadow-2xs">
          <CardContent className="p-5 pt-5 flex items-center justify-between">
            <div>
              <p className="text-xs text-muted-foreground font-medium">今日核销数</p>
              <h3 className="text-2xl font-bold text-foreground mt-1 font-mono">{data.todayVerified}</h3>
            </div>
            <div className="h-9 w-9 rounded-md bg-primary/10 text-primary flex items-center justify-center">
              <CheckCircle className="h-5 w-5" />
            </div>
          </CardContent>
        </Card>

        <Link to="/admin/reconcile?tab=diff" aria-label="查看未收敛库存差异" className="rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2">
          <Card className="h-full shadow-2xs hover:border-amber-400 transition-colors">
            <CardContent className="p-5 pt-5 flex items-center justify-between">
              <div>
                <div className="flex items-center gap-1 text-xs text-muted-foreground font-medium">
                  <span>未收敛差异数</span>
                  <ArrowUpRight className="h-3 w-3 text-amber-700" />
                </div>
                <h3 className={`text-2xl font-bold mt-1 font-mono ${diffNum > 0 ? 'text-amber-800' : 'text-foreground'}`}>
                  {data.reconcileDiffCount}
                </h3>
              </div>
              <div className="h-9 w-9 rounded-md bg-warning/20 text-warning-foreground flex items-center justify-center">
                <AlertTriangle className="h-5 w-5" />
              </div>
            </CardContent>
          </Card>
        </Link>

        <Link to="/admin/reconcile?tab=stuck" aria-label="查看卡单列表" className="rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2">
          <Card className="h-full shadow-2xs hover:border-rose-400 transition-colors">
            <CardContent className="p-5 pt-5 flex items-center justify-between">
              <div>
                <div className="flex items-center gap-1 text-xs text-muted-foreground font-medium">
                  <span>卡单笔数</span>
                  <ArrowUpRight className="h-3 w-3 text-rose-700" />
                </div>
                <h3 className={`text-2xl font-bold mt-1 font-mono ${stuckNum > 0 ? 'text-destructive' : 'text-foreground'}`}>
                  {data.stuckCount}
                </h3>
              </div>
              <div className="h-9 w-9 rounded-md bg-destructive/10 text-destructive flex items-center justify-center">
                <Database className="h-5 w-5" />
              </div>
            </CardContent>
          </Card>
        </Link>
      </div>

      <Card className="shadow-2xs border">
        <CardContent className="p-6 pt-6 space-y-3">
          <div className="flex justify-between items-center text-sm font-bold text-foreground">
            <span>今日游览入园核销率</span>
            <span className="font-mono text-primary">{verifyRatio}%</span>
          </div>
          <Progress value={verifyRatio} max={100} className="h-3" />
          <div className="flex justify-between text-xs text-muted-foreground font-mono">
            <span>已入园核销: {data.todayVerified} 人</span>
            <span>当日总预约: {data.todayReservations} 人</span>
          </div>
        </CardContent>
      </Card>

      <p className="text-xs text-muted-foreground">
        当前接口仅提供业务汇总、差异数和卡单数；QPS、Redis CPU、MQ 积压等运行指标尚未接入，因此不在页面展示虚构数据。
      </p>
    </div>
  )
}
