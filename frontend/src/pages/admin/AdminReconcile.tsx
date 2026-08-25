import { useState, useEffect, useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import { RefreshCw, Info, CheckCircle2 } from 'lucide-react'
import { adminApi, type DeadLetterItem, type ReconcileItem, type StuckItem } from '@/api/admin'
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
import { PageHeader } from '@/components/common/PageHeader'
import { AlertDialog } from '@/components/ui/alert-dialog'

export default function AdminReconcile() {
  const [searchParams, setSearchParams] = useSearchParams()
  const requestedTab = searchParams.get('tab')
  const initialTab = requestedTab === 'latest' || requestedTab === 'stuck' || requestedTab === 'dlq'
    ? requestedTab : 'diff'
  const [tab, setTab] = useState<string>(initialTab)

  const [diffList, setDiffList] = useState<ReconcileItem[] | null>(null)
  const [latestList, setLatestList] = useState<ReconcileItem[] | null>(null)
  const [stuckList, setStuckList] = useState<StuckItem[] | null>(null)
  const [deadLetters, setDeadLetters] = useState<DeadLetterItem[] | null>(null)
  const [acting, setActing] = useState<string>('')
  const [confirmAction, setConfirmAction] = useState<
    | { kind: 'rollback'; id: Id; label: string }
    | { kind: 'replay'; id: string; label: string }
    | null
  >(null)

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
        .listDeadLetters()
        .then((data) => {
          setDeadLetters(data)
          setLoading(false)
        })
        .catch((err) => {
          setLoading(false)
          if (isApiError(err)) {
            setErrorMsg(err.message)
            setRequestId(err.requestId)
          } else {
            setErrorMsg('获取死信消息失败')
          }
        })
    }
  }, [tab])

  useEffect(() => {
    loadData()
  }, [loadData])

  const doAction = (type: 'stuck', id: Id, action: string) => {
    const key = `${action}-${id}`
    setActing(key)
    adminApi
      .reconcileAction(type, id)
      .then(() => {
        setActing('')
        toast.success('卡单已完成回滚处置', '处置完成')
        loadData()
      })
      .catch((err) => {
        setActing('')
        toast.error(isApiError(err) ? err.message : '处置失败')
      })
  }

  const replayDeadLetter = (messageId: string) => {
    setActing(`replay-${messageId}`)
    adminApi.replayDeadLetter(messageId)
      .then(() => {
        setActing('')
        toast.success('消息已重新投递到原业务 Topic', '重放完成')
        loadData()
      })
      .catch((err) => {
        setActing('')
        toast.error(isApiError(err) ? err.message : '死信重放失败')
      })
  }

  const confirmPendingAction = () => {
    if (!confirmAction) return
    const action = confirmAction
    setConfirmAction(null)
    if (action.kind === 'rollback') doAction('stuck', action.id, 'rollback')
    else replayDeadLetter(action.id)
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="对账中心"
        description="监控 Redis 与 DB 差异，并处置卡单和死信"
        actions={(
          <Button variant="outline" size="sm" onClick={loadData} disabled={loading || Boolean(acting)} className="gap-1.5">
            <RefreshCw className="h-4 w-4" />
            <span>刷新</span>
          </Button>
        )}
      />

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
        <TabsList className="w-full justify-start overflow-x-auto">
          <TabsTrigger value="diff" disabled={Boolean(acting)}>当前差异</TabsTrigger>
          <TabsTrigger value="latest" disabled={Boolean(acting)}>最近对账</TabsTrigger>
          <TabsTrigger value="stuck" disabled={Boolean(acting)}>卡单处置</TabsTrigger>
          <TabsTrigger value="dlq" disabled={Boolean(acting)}>死信处置</TabsTrigger>
        </TabsList>

        <TabsContent value="diff" className="mt-4">
          <ReconcileTableShell
            loading={loading}
            errorMsg={errorMsg}
            requestId={requestId}
            onRetry={loadData}
          >
            <Table className="min-w-[860px]">
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
                      当前查询范围内未发现库存差异。
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
            <Table className="min-w-[860px]">
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
            <Table className="min-w-[980px]">
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
                        ) : item.status === 4 ? (
                          <Badge variant="warning">回滚待恢复</Badge>
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
                            disabled={Boolean(acting) || (item.status !== 0 && item.status !== 4)}
                            onClick={() => setConfirmAction({ kind: 'rollback', id: item.reservationNo, label: item.reservationNo })}>
                            {acting === `rollback-${item.reservationNo}` ? '...' : item.status === 4 ? '重试回滚' : '人工回滚'}
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

        <TabsContent value="dlq" className="mt-4">
          <ReconcileTableShell
            loading={loading}
            errorMsg={errorMsg}
            requestId={requestId}
            onRetry={loadData}
          >
            <Table className="min-w-[920px]">
              <TableHeader>
                <TableRow>
                  <TableHead>消息 ID</TableHead>
                  <TableHead>来源消费组</TableHead>
                  <TableHead>目标 Topic</TableHead>
                  <TableHead>重试次数</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>捕获时间</TableHead>
                  <TableHead className="text-right">处置</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {!deadLetters || deadLetters.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={7} className="text-center py-8 text-muted-foreground">
                      <CheckCircle2 className="h-6 w-6 text-emerald-600 inline mr-2" />
                      当前没有死信消息。
                    </TableCell>
                  </TableRow>
                ) : deadLetters.map((item) => (
                  <TableRow key={item.messageId}>
                    <TableCell className="font-mono text-xs max-w-48 truncate">{item.messageId}</TableCell>
                    <TableCell className="font-mono text-xs">{item.sourceGroup}</TableCell>
                    <TableCell className="font-mono text-xs">{item.targetTopic}</TableCell>
                    <TableCell>{item.reconsumeTimes}</TableCell>
                    <TableCell>
                      <Badge variant={item.status === 'REPLAYED' ? 'success' : 'warning'}>
                        {item.status === 'PENDING' ? '待重放' : item.status === 'REPLAYING' ? '重放中' : '已重放'}
                      </Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{item.capturedAt}</TableCell>
                    <TableCell className="text-right">
                      <Button size="sm" variant="outline" className="text-xs"
                        disabled={Boolean(acting) || item.status === 'REPLAYED'}
                        onClick={() => setConfirmAction({ kind: 'replay', id: item.messageId, label: item.messageId })}>
                        {acting === `replay-${item.messageId}` ? '...' : '重放'}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </ReconcileTableShell>
        </TabsContent>

      </Tabs>

      <AlertDialog
        open={confirmAction !== null}
        onOpenChange={(open) => { if (!open && !acting) setConfirmAction(null) }}
        title={confirmAction?.kind === 'rollback' ? '确认人工回滚卡单？' : '确认重放死信消息？'}
        description={confirmAction?.kind === 'rollback'
          ? `预约 ${confirmAction.label} 将进入回滚流程，可能释放预占并改变库存状态。仅在确认数据库未成功落库后执行。`
          : `消息 ${confirmAction?.label ?? ''} 将再次投递到原业务 Topic，消费者会按幂等逻辑重新处理。`}
        confirmText={confirmAction?.kind === 'rollback' ? '确认回滚' : '确认重放'}
        variant={confirmAction?.kind === 'rollback' ? 'destructive' : 'default'}
        busy={Boolean(acting)}
        onConfirm={confirmPendingAction}
      />
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

  return <div className="overflow-hidden rounded-lg border bg-card">{children}</div>
}
