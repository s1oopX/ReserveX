import { useState, useEffect } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Ticket, QrCode, Eye, XCircle, Calendar, GitBranch } from 'lucide-react'
import { reservationApi, type ReservationVO } from '@/api/reservation'
import { isApiError } from '@/api/http'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Skeleton } from '@/components/ui/skeleton'
import { ReservationStatusBadge } from '@/components/common/StatusBadge'
import { EmptyState } from '@/components/common/EmptyState'
import { ErrorState } from '@/components/common/ErrorState'
import { AlertDialog } from '@/components/ui/alert-dialog'
import { toast } from '@/components/ui/sonner'
import { PageHeader } from '@/components/common/PageHeader'

export default function MyReservations() {
  const nav = useNavigate()
  const [list, setList] = useState<ReservationVO[] | null>(null)
  const [filter, setFilter] = useState<'all' | 'pending' | 'ended'>('all')
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')

  const [cancelTarget, setCancelTarget] = useState<ReservationVO | null>(null)
  const [cancelBusy, setCancelBusy] = useState<boolean>(false)

  const loadData = () => {
    setLoading(true)
    setErrorMsg('')
    reservationApi
      .mine()
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
          setErrorMsg('获取我的预约列表失败')
        }
      })
  }

  useEffect(() => {
    loadData()
  }, [])

  const handleConfirmCancel = async () => {
    if (!cancelTarget || cancelBusy) return
    setCancelBusy(true)
    try {
      await reservationApi.cancel(cancelTarget.reservationNo, cancelTarget.version)
      toast.success('预约已成功取消')
      setCancelTarget(null)
      loadData()
    } catch (err) {
      if (isApiError(err)) {
        toast.error(err.message)
      } else {
        toast.error('取消预约失败，请重试')
      }
    } finally {
      setCancelBusy(false)
    }
  }

  const filteredList = list?.filter((item) => {
    if (filter === 'pending') {
      return item.status === 'CONFIRMED' || item.status === 'PENDING' || item.status === 'REVIEW_REQUIRED'
    }
    if (filter === 'ended') {
      return item.status === 'VERIFIED' || item.status === 'CANCELLED' || item.status === 'EXPIRED' || item.status === 'FAILED'
    }
    return true
  })

  return (
    <div className="mx-auto max-w-4xl space-y-7 font-sans">
      <PageHeader title="我的预约" description="查看预约状态、凭证和异步确认结果" actions={<Tabs value={filter} onValueChange={(v) => setFilter(v as 'all' | 'pending' | 'ended')}><TabsList><TabsTrigger value="all" className="text-xs">全部</TabsTrigger><TabsTrigger value="pending" className="text-xs">待入园</TabsTrigger><TabsTrigger value="ended" className="text-xs">已结束</TabsTrigger></TabsList></Tabs>} />

      {loading && (
        <div className="space-y-4">
          {[1, 2, 3].map((i) => (
            <Card key={i} className="space-y-3 p-5">
              <div className="flex justify-between">
                <Skeleton className="h-5 w-32" />
                <Skeleton className="h-5 w-16" />
              </div>
              <Skeleton className="h-4 w-48" />
              <Skeleton className="h-10 w-full mt-2" />
            </Card>
          ))}
        </div>
      )}

      {errorMsg && (
        <ErrorState
          title="无法获取预约记录"
          message={errorMsg}
          requestId={requestId}
          onRetry={loadData}
        />
      )}

      {!loading && !errorMsg && filteredList && (
        filteredList.length === 0 ? (
          <EmptyState
            icon={<Ticket className="h-8 w-8 text-muted-foreground" />}
            title="暂无相关预约记录"
            description="您当前没有符合筛选条件的预约记录。"
            actionLabel="去预约场次"
            onAction={() => nav('/slots')}
          />
        ) : (
          <div className="space-y-4">
            {filteredList.map((item) => {
              const canQr = item.status === 'CONFIRMED' || item.status === 'PENDING'
              const canCancel = item.status === 'CONFIRMED' || item.status === 'PENDING'

              return (
                <Card key={item.reservationNo} className="overflow-hidden rounded-2xl border-slate-200 bg-white shadow-sm transition-shadow hover:shadow-md">
                  <CardContent className="space-y-4 p-5 sm:p-6">
                    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 pb-4">
                      <div className="flex items-center gap-2">
                        <Calendar className="h-4 w-4 text-primary" />
                        <span className="font-serif text-xl font-semibold text-[#123b43]">{item.slotDate}</span>
                        <span className="font-mono text-xs text-muted-foreground">
                          ({String(item.slotHour).padStart(2, '0')}:00 场次)
                        </span>
                      </div>
                      <ReservationStatusBadge status={item.status} />
                    </div>

                    <div className="grid gap-2.5 font-mono text-xs text-muted-foreground">
                      <div className="flex justify-between items-center">
                        <span>预约单号</span>
                        <span className="rounded bg-muted px-2 py-0.5 text-[11.5px] font-semibold text-foreground">{item.reservationNo}</span>
                      </div>
                      <div className="flex justify-between items-center">
                        <span>提交时间</span>
                        <span>{item.createAt}</span>
                      </div>
                      {item.verifyTime && (
                        <div className="flex justify-between items-center">
                          <span>核销时间</span>
                          <span className="font-semibold text-primary">{item.verifyTime}</span>
                        </div>
                      )}
                    </div>

                    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-4">
                      <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                        <GitBranch className="h-3.5 w-3.5 text-primary" />
                        <span>{item.status === 'PENDING' ? 'Redis 预占，等待持久化' : '状态已写入预约记录'}</span>
                      </div>
                      <div className="flex flex-wrap items-center justify-end gap-2">
                      <Button asChild variant="outline" size="sm" className="gap-1.5 text-xs">
                        <Link to={`/reservation/${item.reservationNo}`}>
                          <Eye className="h-3.5 w-3.5 text-slate-500" />
                          <span>详情</span>
                        </Link>
                      </Button>

                      {canQr && (
                        <Button asChild size="sm" className="gap-1.5 text-xs font-semibold">
                          <Link to={`/reservation/${item.reservationNo}/qr`}>
                            <QrCode className="h-3.5 w-3.5" />
                            <span>入园二维码</span>
                          </Link>
                        </Button>
                      )}

                      {canCancel && (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => setCancelTarget(item)}
                          className="gap-1.5 border-destructive/30 text-xs text-destructive hover:bg-destructive/10"
                        >
                          <XCircle className="h-3.5 w-3.5" />
                          <span>取消预约</span>
                        </Button>
                      )}
                      </div>
                    </div>
                  </CardContent>
                </Card>
              )
            })}
          </div>
        )
      )}

      <AlertDialog
        open={Boolean(cancelTarget)}
        onOpenChange={(open) => !open && setCancelTarget(null)}
        title="确认取消该笔预约？"
        variant="destructive"
        confirmText="确认取消"
        cancelText="暂不取消"
        busy={cancelBusy}
        onConfirm={handleConfirmCancel}
        description={
          cancelTarget ? (
            <div className="mt-2 space-y-2 text-sm text-foreground">
              <p>您正在申请取消预约单号为 <strong className="font-mono">{cancelTarget.reservationNo}</strong> 的预约。</p>
              <div className="space-y-1 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-xs font-medium text-destructive">
                <div>⚠️ 取消须知：</div>
                <div>1. 名额取消后不可恢复，且不会返还至名额池中；</div>
                <div>2. 您今天将无法重新提交任何场次的预约；</div>
                <div>3. 此操作确认后不可撤销。</div>
              </div>
            </div>
          ) : null
        }
      />
    </div>
  )
}
