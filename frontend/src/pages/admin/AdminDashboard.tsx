import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Calendar, TicketCheck, CheckCircle, AlertTriangle, Database, ArrowUpRight } from 'lucide-react'
import { adminApi, type DashboardVO } from '@/api/admin'
import { isApiError } from '@/api/http'
import { Card, CardContent } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'

export default function AdminDashboard() {
  const nav = useNavigate()
  const [data, setData] = useState<DashboardVO | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')

  const loadDashboard = () => {
    setLoading(true)
    setErrorMsg('')
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
      <ErrorState
        title="驾驶舱看板数据加载失败"
        message={errorMsg}
        requestId={requestId}
        onRetry={loadDashboard}
      />
    )
  }

  if (!data) return null

  const todayResNum = Number(data.todayReservations) || 0
  const todayVerNum = Number(data.todayVerified) || 0
  const diffNum = Number(data.reconcileDiffCount) || 0
  const stuckNum = Number(data.stuckCount) || 0

  const verifyRatio = todayResNum > 0 ? Math.round((todayVerNum / todayResNum) * 100) : 0

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-foreground font-serif">
          数据驾驶舱
        </h1>
        <p className="text-xs text-muted-foreground mt-0.5">
          湿地公园核心场次、预约量、核销率及库存不一致度指标
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <Card className="shadow-2xs">
          <CardContent className="p-5 flex items-center justify-between">
            <div>
              <p className="text-xs text-muted-foreground font-medium">今日场次数</p>
              <h3 className="text-2xl font-bold text-foreground mt-1 font-mono">{data.todaySlots}</h3>
            </div>
            <div className="h-10 w-10 rounded-full bg-primary/10 text-primary flex items-center justify-center">
              <Calendar className="h-5 w-5" />
            </div>
          </CardContent>
        </Card>

        <Card className="shadow-2xs">
          <CardContent className="p-5 flex items-center justify-between">
            <div>
              <p className="text-xs text-muted-foreground font-medium">今日预约数</p>
              <h3 className="text-2xl font-bold text-foreground mt-1 font-mono">{data.todayReservations}</h3>
            </div>
            <div className="h-10 w-10 rounded-full bg-emerald-500/10 text-emerald-700 flex items-center justify-center">
              <TicketCheck className="h-5 w-5" />
            </div>
          </CardContent>
        </Card>

        <Card className="shadow-2xs">
          <CardContent className="p-5 flex items-center justify-between">
            <div>
              <p className="text-xs text-muted-foreground font-medium">今日核销数</p>
              <h3 className="text-2xl font-bold text-foreground mt-1 font-mono">{data.todayVerified}</h3>
            </div>
            <div className="h-10 w-10 rounded-full bg-teal-500/10 text-teal-700 flex items-center justify-center">
              <CheckCircle className="h-5 w-5" />
            </div>
          </CardContent>
        </Card>

        <Card
          onClick={() => nav('/admin/reconcile?tab=diff')}
          className="shadow-2xs cursor-pointer hover:border-amber-400 transition-colors"
        >
          <CardContent className="p-5 flex items-center justify-between">
            <div>
              <div className="flex items-center gap-1 text-xs text-muted-foreground font-medium">
                <span>未收敛差异数</span>
                <ArrowUpRight className="h-3 w-3 text-amber-700" />
              </div>
              <h3 className={`text-2xl font-bold mt-1 font-mono ${diffNum > 0 ? 'text-amber-800' : 'text-foreground'}`}>
                {data.reconcileDiffCount}
              </h3>
            </div>
            <div className="h-10 w-10 rounded-full bg-amber-500/10 text-amber-700 flex items-center justify-center">
              <AlertTriangle className="h-5 w-5" />
            </div>
          </CardContent>
        </Card>

        <Card
          onClick={() => nav('/admin/reconcile?tab=stuck')}
          className="shadow-2xs cursor-pointer hover:border-rose-400 transition-colors"
        >
          <CardContent className="p-5 flex items-center justify-between">
            <div>
              <div className="flex items-center gap-1 text-xs text-muted-foreground font-medium">
                <span>卡单笔数</span>
                <ArrowUpRight className="h-3 w-3 text-rose-700" />
              </div>
              <h3 className={`text-2xl font-bold mt-1 font-mono ${stuckNum > 0 ? 'text-destructive' : 'text-foreground'}`}>
                {data.stuckCount}
              </h3>
            </div>
            <div className="h-10 w-10 rounded-full bg-rose-500/10 text-rose-700 flex items-center justify-center">
              <Database className="h-5 w-5" />
            </div>
          </CardContent>
        </Card>
      </div>

      <Card className="shadow-2xs border">
        <CardContent className="p-6 space-y-3">
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
    </div>
  )
}
