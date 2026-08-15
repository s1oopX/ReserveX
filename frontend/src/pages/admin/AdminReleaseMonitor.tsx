import { useState, useEffect, useCallback } from 'react'
import { Activity, RefreshCw, CheckCircle2, XCircle, AlertTriangle } from 'lucide-react'
import { adminApi, type ReleaseMonitorItem } from '@/api/admin'
import { isApiError } from '@/api/http'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'
import { EmptyState } from '@/components/common/EmptyState'

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
  const abnormal = list?.filter((s) => !s.metaComplete || s.bucketPresent !== s.bucketExpected) ?? []
  const released = list?.filter((s) => s.released && s.metaComplete && s.bucketPresent === s.bucketExpected) ?? []

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between border-b pb-3">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-foreground font-serif">
            放号发布监控
          </h1>
          <p className="text-xs text-muted-foreground mt-0.5">
            今日与次日场次的 DB 状态、Redis 元数据完整度与分桶预热状态
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={load} className="gap-1.5 w-fit">
          <RefreshCw className="h-4 w-4" />
          <span>刷新</span>
        </Button>
      </div>

      {(loading || errorMsg || !list) && (
        <Card className="p-6 space-y-3">
          {loading && <><Skeleton className="h-6 w-full" /><Skeleton className="h-6 w-full" /><Skeleton className="h-6 w-full" /></>}
          {errorMsg && <ErrorState title="加载放号监控失败" message={errorMsg} requestId={requestId} onRetry={load} />}
        </Card>
      )}

      {list && (
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
      <Card className="shadow-2xs border">
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
      </Card>
    )
  }
  return (
    <Card className="shadow-2xs border">
      <div className="p-4 border-b">
        <h2 className="text-sm font-semibold text-foreground flex items-center gap-2">
          {abnormal ? <AlertTriangle className="h-4 w-4 text-amber-600" /> : <Activity className="h-4 w-4 text-primary" />}
          {title}
          <Badge variant="secondary" className="ml-auto">{items.length}</Badge>
        </h2>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>场次 ID</TableHead>
            <TableHead>日期与时段</TableHead>
            <TableHead>放号状态</TableHead>
            <TableHead>容量 / 剩余</TableHead>
            <TableHead>Redis 元数据</TableHead>
            <TableHead>分桶预热</TableHead>
            <TableHead>版本</TableHead>
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
                  <span className="inline-flex items-center gap-1 text-xs text-emerald-700 font-medium">
                    <CheckCircle2 className="h-3.5 w-3.5" /> Complete
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1 text-xs text-amber-700 font-medium">
                    <XCircle className="h-3.5 w-3.5" /> Incomplete
                  </span>
                )}
              </TableCell>
              <TableCell className="font-mono text-xs">
                {s.bucketPresent}/{s.bucketExpected}
                {s.bucketPresent !== s.bucketExpected && (
                  <span className="text-destructive ml-1">⚠</span>
                )}
              </TableCell>
              <TableCell className="font-mono text-xs">v{s.version}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Card>
  )
}
