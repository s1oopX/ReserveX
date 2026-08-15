import { useState, useEffect, useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import { RefreshCw, Info, CheckCircle2 } from 'lucide-react'
import { adminApi, type ReconcileItem, type StuckItem, type DlqView } from '@/api/admin'
import type { Id } from '@/api/http'
import { isApiError } from '@/api/http'
import { toast } from '@/components/ui/sonner'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'

export default function AdminReconcile() {
  const [searchParams, setSearchParams] = useSearchParams()
  const initialTab = searchParams.get('tab') || 'diff'
  const [tab, setTab] = useState<string>(initialTab)

  const [diffList, setDiffList] = useState<ReconcileItem[] | null>(null)
  const [latestList, setLatestList] = useState<ReconcileItem[] | null>(null)
  const [stuckList, setStuckList] = useState<StuckItem[] | null>(null)
  const [dlqView, setDlqView] = useState<DlqView | null>(null)
  const [acting, setActing] = useState<string>('')

  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')

  const loadData = useCallback(() => {
    setLoading(true)
    setErrorMsg('')
    setRequestId('')

    if (tab === 'diff') {
      adminApi
        .reconcileDiff()
        .then((data) => {
          setDiffList(data)
          setLoading(false)
        })
        .catch((err) => {
          setLoading(false)
          if (isApiError(err)) {
            setErrorMsg(err.message)
            setRequestId(err.requestId)
          } else {
            setErrorMsg('获取库存差异对账明细失败')
          }
        })
    } else if (tab === 'latest') {
      adminApi
        .reconcileLatest()
        .then((data) => {
          setLatestList(data)
          setLoading(false)
        })
        .catch((err) => {
          setLoading(false)
          if (isApiError(err)) {
            setErrorMsg(err.message)
            setRequestId(err.requestId)
          } else {
            setErrorMsg('获取最新对账日志失败')
          }
        })
    } else if (tab === 'stuck') {
      adminApi
        .reconcileStuck()
        .then((data) => {
          setStuckList(data)
          setLoading(false)
        })
        .catch((err) => {
          setLoading(false)
          if (isApiError(err)) {
            setErrorMsg(err.message)
            setRequestId(err.requestId)
          } else {
            setErrorMsg('获取卡单明细失败')
          }
        })
    } else if (tab === 'dlq') {
      adminApi
        .reconcileDlq()
        .then((data) => {
          setDlqView(data)
          setLoading(false)
        })
        .catch((err) => {
          setLoading(false)
          if (isApiError(err)) {
            setErrorMsg(err.message)
            setRequestId(err.requestId)
          } else {
            setErrorMsg('获取死信队列失败')
          }
        })
    }
  }, [tab])

  useEffect(() => {
    loadData()
  }, [loadData])

  const doAction = (type: 'diff' | 'stuck' | 'dlq', id: Id, action: string) => {
    const key = `${action}-${id}`
    setActing(key)
    adminApi
      .reconcileAction(type, id, action)
      .then((affected) => {
        setActing('')
        toast.success(action === 'rollback' ? `已回滚 (受影响 ${affected})` : `已处理 (受影响 ${affected})`, '处置完成')
        loadData()
      })
      .catch((err) => {
        setActing('')
        toast.error(isApiError(err) ? err.message : '处置失败')
      })
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between border-b pb-4">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-foreground font-serif">
            对账中心与对齐控制
          </h1>
          <p className="text-xs text-muted-foreground mt-0.5">
            监控 Redis 与 DB 差异、卡单转人工及 DLQ 消息
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={loadData} className="gap-1.5">
          <RefreshCw className="h-4 w-4" />
          <span>刷新此 Tab</span>
        </Button>
      </div>

      <Alert variant="warning" className="border-amber-300 bg-amber-50">
        <Info className="h-4 w-4 text-amber-700" />
        <AlertDescription className="text-xs text-amber-900 font-medium leading-relaxed">
          <strong>对账说明：</strong> 单次差异可能来自读取时点不同（在途消息），应重点关注<strong>连续多个周期未收敛的差异</strong>。
        </AlertDescription>
      </Alert>

      <Tabs
        value={tab}
        onValueChange={(v) => {
          setTab(v)
          setSearchParams({ tab: v })
        }}
      >
        <TabsList className="w-full justify-start">
          <TabsTrigger value="diff">库存差异记录</TabsTrigger>
          <TabsTrigger value="latest">最新全量对账日志</TabsTrigger>
          <TabsTrigger value="stuck">卡单列表 (stuck)</TabsTrigger>
          <TabsTrigger value="dlq">死信队列 (DLQ)</TabsTrigger>
        </TabsList>

        <TabsContent value="diff" className="mt-4">
          <ReconcileTableShell
            loading={loading}
            errorMsg={errorMsg}
            requestId={requestId}
            onRetry={loadData}
          >
            <Table>
              <TableHeader>
                <TableRow>
                  <TableRowDiffHeaders />
                </TableRow>
              </TableHeader>
              <TableBody>
                {!diffList || diffList.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={7} className="text-center py-8 text-muted-foreground">
                      <CheckCircle2 className="h-6 w-6 text-emerald-600 inline mr-2" />
                      当前无库存差异，全园 Redis 与 DB 账目完美一致。
                    </TableCell>
                  </TableRow>
                ) : (
                  diffList.map((item) => (
                    <TableRowDiffItem key={item.id} item={item} />
                  ))
                )}
              </TableBody>
            </Table>
          </ReconcileTableShell>
        </TabsContent>

        <TabsContent value="latest" className="mt-4">
          <ReconcileTableShell
            loading={loading}
            errorMsg={errorMsg}
            requestId={requestId}
            onRetry={loadData}
          >
            <Table>
              <TableHeader>
                <TableRow>
                  <TableRowDiffHeaders />
                </TableRow>
              </TableHeader>
              <TableBody>
                {!latestList || latestList.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={7} className="text-center py-8 text-muted-foreground">
                      暂无最新对账日志记录。
                    </TableCell>
                  </TableRow>
                ) : (
                  latestList.map((item) => (
                    <TableRowDiffItem key={item.id} item={item} />
                  ))
                )}
              </TableBody>
            </Table>
          </ReconcileTableShell>
        </TabsContent>

        <TabsContent value="stuck" className="mt-4">
          <ReconcileTableShell
            loading={loading}
            errorMsg={errorMsg}
            requestId={requestId}
            onRetry={loadData}
          >
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>预约编号 (rno)</TableHead>
                  <TableHead>场次 ID</TableHead>
                  <TableHead>补投次数</TableHead>
                  <TableHead>最后错误原因</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>创建时间</TableHead>
                  <TableHead className="text-right">处置操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {!stuckList || stuckList.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={7} className="text-center py-8 text-muted-foreground">
                      <CheckCircle2 className="h-6 w-6 text-emerald-600 inline mr-2" />
                      当前无待研判的卡单记录。
                    </TableCell>
                  </TableRow>
                ) : (
                  stuckList.map((item) => (
                    <TableRow key={item.reservationNo}>
                      <TableCell className="font-mono text-xs font-bold text-foreground">
                        {item.reservationNo}
                      </TableCell>
                      <TableCell className="font-mono text-xs text-muted-foreground">{item.slotId}</TableCell>
                      <TableCell className="font-mono">{item.reinjectCount} 次</TableCell>
                      <TableCell className="font-mono text-xs text-destructive max-w-xs truncate">
                        {item.lastError || 'N/A'}
                      </TableCell>
                      <TableCell>
                        {item.status === 0 ? (
                          <Badge variant="warning">待研判</Badge>
                        ) : item.status === 1 ? (
                          <Badge variant="success">已成功重投</Badge>
                        ) : (
                          <Badge variant="outline">已回滚/忽略</Badge>
                        )}
                      </TableCell>
                      <TableCell className="font-mono text-xs">{item.createAt}</TableCell>
                      <TableCell className="text-right">
                        <div className="flex gap-1 justify-end">
                          <Button size="sm" variant="outline" className="text-xs"
                            disabled={item.status !== 0 || acting === `rollback-${item.reservationNo}`}
                            onClick={() => doAction('stuck', item.reservationNo, 'rollback')}>
                            {acting === `rollback-${item.reservationNo}` ? '...' : '人工回滚'}
                          </Button>
                          <Button size="sm" variant="outline" className="text-xs"
                            disabled={item.status !== 0 || acting === `ignore-${item.reservationNo}`}
                            onClick={() => doAction('stuck', item.reservationNo, 'ignore')}>
                            {acting === `ignore-${item.reservationNo}` ? '...' : '忽略'}
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </ReconcileTableShell>
        </TabsContent>

        <TabsContent value="dlq" className="mt-4 space-y-4">
          <Alert variant="warning" className="border-amber-300 bg-amber-50">
            <Info className="h-4 w-4 text-amber-700" />
            <AlertDescription className="text-xs text-amber-900 font-medium leading-relaxed">
              <strong>DLQ 监控说明：</strong>真实死信队列监控需 RocketMQ Dashboard。
              {dlqView && (
                <>当前待研判卡单数：<strong>{dlqView.stuckCount}</strong>。请前往「卡单列表」处置。</>
              )}
              {dlqView?.reason && <><br />{dlqView.reason}</>}
            </AlertDescription>
          </Alert>
          <ReconcileTableShell
            loading={loading}
            errorMsg={errorMsg}
            requestId={requestId}
            onRetry={loadData}
          >
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Topic</TableHead>
                  <TableHead>业务编号</TableHead>
                  <TableHead>重试次数</TableHead>
                  <TableHead>最后错误</TableHead>
                  <TableHead>消息摘要</TableHead>
                  <TableHead className="text-right">死信处置</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                <TableRow>
                  <TableCell colSpan={6} className="text-center py-8 text-muted-foreground">
                    死信队列拉取接口当前返回空列表。请用「卡单列表」tab 查看待研判项。
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </ReconcileTableShell>
        </TabsContent>
      </Tabs>
    </div>
  )
}

function TableRowDiffHeaders() {
  return (
    <>
      <TableHead>对账周期 PERIOD</TableHead>
      <TableHead>场次 ID</TableHead>
      <TableHead>Redis 已占用</TableHead>
      <TableHead>DB 桶已占用</TableHead>
      <TableHead>有效预约数</TableHead>
      <TableHead>Diff 差值</TableHead>
      <TableHead>对账时间</TableHead>
    </>
  )
}

function TableRowDiffItem({ item }: { item: ReconcileItem }) {
  const isDiff = item.diff !== 0
  const redisOcc = item.redisOccupied ?? '-'
  const dbOcc = item.dbOccupied ?? '-'
  const resCnt = item.reservationCnt ?? '-'

  return (
    <TableRow className={isDiff ? 'bg-amber-50/50' : ''}>
      <TableCell className="font-mono text-xs font-semibold">{item.period}</TableCell>
      <TableCell className="font-mono text-xs text-muted-foreground">{item.slotId}</TableCell>
      <TableCell className="font-mono">{redisOcc}</TableCell>
      <TableCell className="font-mono">{dbOcc}</TableCell>
      <TableCell className="font-mono text-emerald-800 font-semibold">{resCnt}</TableCell>
      <TableCell className="font-mono">
        {isDiff ? (
          <Badge variant="destructive">{item.diff}</Badge>
        ) : (
          <Badge variant="outline" className="text-emerald-700 border-emerald-300">0 (收敛)</Badge>
        )}
      </TableCell>
      <TableCell className="font-mono text-xs text-muted-foreground">{item.createAt}</TableCell>
    </TableRow>
  )
}

function ReconcileTableShell({
  loading,
  errorMsg,
  requestId,
  onRetry,
  children,
}: {
  loading: boolean
  errorMsg: string
  requestId: string
  onRetry: () => void
  children: React.ReactNode
}) {
  if (loading) {
    return (
      <Card className="p-6 space-y-3">
        <Skeleton className="h-6 w-full" />
        <Skeleton className="h-6 w-full" />
        <Skeleton className="h-6 w-full" />
      </Card>
    )
  }

  if (errorMsg) {
    return (
      <ErrorState
        title="加载对账数据失败"
        message={errorMsg}
        requestId={requestId}
        onRetry={onRetry}
      />
    )
  }

  return <Card className="shadow-2xs border">{children}</Card>
}
