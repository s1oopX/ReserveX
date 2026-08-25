import { useState, useEffect, useCallback } from 'react'
import { Activity, RefreshCw, CheckCircle2, XCircle, AlertTriangle, ArrowRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import { adminApi, type ReleaseMonitorItem } from '@/api/admin'
import { isApiError } from '@/api/http'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'
import { EmptyState } from '@/components/common/EmptyState'
import { PageHeader } from '@/components/common/PageHeader'

export default function AdminReleaseMonitor() {
  const [list, setList] = useState<ReleaseMonitorItem[] | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')

  const load = useCallback(() => {
    setLoading(true)
    setErrorMsg('')
    setRequestId('')
    adminApi
      .releaseMonitor()
      .then((data) => {
        setList(data)
        setLoading(false)
      })
      .catch((err) => {
        setLoading(false)
        if (isApiError(err)) {
          setErrorMsg(err.message)
          setRequestId(err.requestId)
        } else {
          setErrorMsg('获取放号监控数据失败')
        }
      })
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const pending = list?.filter((s) => !s.released) ?? []
  const abnormal = list?.filter((s) => s.released && (!s.metaComplete || s.bucketPresent !== s.bucketExpected)) ?? []
  const released = list?.filter((s) => s.released && s.metaComplete && s.bucketPresent === s.bucketExpected) ?? []
  const issueCount = pending.length + abnormal.length

  return (
    <div className="space-y-6">
      <PageHeader
        title="放号发布监控"
        description="检查今日与次日场次的发布、元数据与分桶状态"
        actions={(
          <Button variant="outline" size="sm" onClick={load} disabled={loading} className="gap-1.5">
            <RefreshCw className="h-4 w-4" />
            <span>刷新</span>
          </Button>
        )}
      />

      {list && !loading && !errorMsg && list.length > 0 && (
        <div className={`flex flex-col gap-2 rounded-lg border p-4 sm:flex-row sm:items-center sm:justify-between ${issueCount ? 'border-amber-300 bg-amber-50/70' : 'border-emerald-200 bg-emerald-50/60'}`}>
          <div className="flex items-center gap-2 text-sm">
            {issueCount ? <AlertTriangle className="h-4 w-4 text-amber-700" /> : <CheckCircle2 className="h-4 w-4 text-emerald-700" />}
            <span className="font-semibold">{issueCount ? `${issueCount} 个场次仍需检查` : '当前场次发布状态正常'}</span>
            <span className="text-xs text-muted-foreground">已发布且元数据、分桶完整：{released.length}</span>
          </div>
          {issueCount > 0 && <Link to="/admin/slots" className="inline-flex items-center gap-1 text-xs font-semibold text-primary hover:underline">打开场次日历 <ArrowRight className="h-3.5 w-3.5" /></Link>}
        </div>
      )}

      {(loading || errorMsg || !list) && (
        <Card className="p-6 space-y-3">
          {loading && <><Skeleton className="h-6 w-full" /><Skeleton className="h-6 w-full" /><Skeleton className="h-6 w-full" /></>}
          {errorMsg && <ErrorState title="加载放号监控失败" message={errorMsg} requestId={requestId} onRetry={load} />}
        </Card>
      )}

      {list && !loading && !errorMsg && (
        <div className="space-y-6">
          <ReleaseSection title="待发布场次" items={pending} emptyHint="暂无待发布场次" />
          <ReleaseSection title="发布异常（Redis 元数据缺失或分桶不完整）" items={abnormal} emptyHint="暂无异常告警" abnormal />
          <ReleaseSection title="已正常发布" items={released} emptyHint="暂无发布记录" />
        </div>
      )}
    </div>
  )
}

function ReleaseSection({ title, items, emptyHint, abnormal }: {
  title: string
  items: ReleaseMonitorItem[]
  emptyHint: string
  abnormal?: boolean
}) {
  if (items.length === 0) {
    return (
      <section className="overflow-hidden rounded-lg border bg-card">
        <div className="p-4 border-b">
          <h2 className="text-sm font-semibold text-foreground flex items-center gap-2">
            {abnormal ? <AlertTriangle className="h-4 w-4 text-amber-600" /> : <Activity className="h-4 w-4 text-primary" />}
            {title}
            <Badge variant="secondary" className="ml-auto">{items.length}</Badge>
          </h2>
        </div>
        <div className="p-6">
          <EmptyState icon={<Activity className="h-8 w-8" />} title={emptyHint} description="" />
        </div>
      </section>
    )
  }
  return (
    <section className={abnormal ? 'overflow-hidden rounded-lg border border-warning/60 bg-card' : 'overflow-hidden rounded-lg border bg-card'}>
      <div className="p-4 border-b">
        <h2 className="text-sm font-semibold text-foreground flex items-center gap-2">
          {abnormal ? <AlertTriangle className="h-4 w-4 text-amber-600" /> : <Activity className="h-4 w-4 text-primary" />}
          {title}
          <Badge variant="secondary" className="ml-auto">{items.length}</Badge>
        </h2>
      </div>
      <Table className="min-w-[920px]">
        <TableHeader>
          <TableRow>
            <TableHead>场次 ID</TableHead>
            <TableHead>日期与时段</TableHead>
            <TableHead>放号状态</TableHead>
            <TableHead>容量 / 剩余</TableHead>
            <TableHead>Redis 元数据</TableHead>
            <TableHead>分桶预热</TableHead>
            <TableHead>版本</TableHead>
            <TableHead className="text-right">下一步</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((s) => (
            <TableRow key={s.slotId}>
              <TableCell className="font-mono text-xs text-muted-foreground">{s.slotId}</TableCell>
              <TableCell className="font-mono text-xs">{s.slotDate} {String(s.slotHour).padStart(2, '0')}:00</TableCell>
              <TableCell>
                {s.released ? (
                  <Badge variant="success" className="gap-1"><CheckCircle2 className="h-3 w-3" />已放号</Badge>
                ) : (
                  <Badge variant="secondary">未放号</Badge>
                )}
              </TableCell>
              <TableCell className="font-mono text-xs">{s.capacity} / {s.redisRemain}</TableCell>
              <TableCell>
                {s.metaComplete ? (
                  <span className="inline-flex items-center gap-1 text-xs text-success-foreground font-medium">
                    <CheckCircle2 className="h-3.5 w-3.5" /> 完整
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1 text-xs text-amber-700 font-medium">
                    <XCircle className="h-3.5 w-3.5" /> 不完整
                  </span>
                )}
              </TableCell>
              <TableCell className="font-mono text-xs">
                {s.bucketPresent}/{s.bucketExpected}
                {s.bucketPresent !== s.bucketExpected && (
                  <AlertTriangle className="ml-1 inline h-3.5 w-3.5 text-destructive" />
                )}
              </TableCell>
              <TableCell className="font-mono text-xs">v{s.version}</TableCell>
              <TableCell className="text-right">
                <Link to={`/admin/slots?date=${s.slotDate}`} className="text-xs font-semibold text-primary hover:underline">查看场次</Link>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </section>
  )
}
