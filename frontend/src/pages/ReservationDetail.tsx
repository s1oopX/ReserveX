import { useState, useEffect, useCallback } from 'react'
import { useParams, Link, useNavigate } from 'react-router-dom'
import { QrCode, ArrowLeft, Clock, Calendar, ShieldCheck, XCircle } from 'lucide-react'
import { reservationApi, type ReservationVO } from '@/api/reservation'
import { isApiError } from '@/api/http'
import { Code } from '@/api/codes'
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { ReservationStatusBadge } from '@/components/common/StatusBadge'
import { ErrorState } from '@/components/common/ErrorState'
import { AlertDialog } from '@/components/ui/alert-dialog'
import { toast } from '@/components/ui/sonner'
import { PageHeader } from '@/components/common/PageHeader'
import { TechnicalTrace } from '@/components/common/TechnicalTrace'

export default function ReservationDetail() {
  const { rno } = useParams<{ rno: string }>()
  const nav = useNavigate()
  const [detail, setDetail] = useState<ReservationVO | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')
  const [is403, setIs403] = useState<boolean>(false)

  const [cancelOpen, setCancelOpen] = useState<boolean>(false)
  const [cancelBusy, setCancelBusy] = useState<boolean>(false)

  const loadData = useCallback(() => {
    if (!rno) return
    setLoading(true)
    setErrorMsg('')
    setIs403(false)

    reservationApi
      .detail(rno)
      .then((data) => {
        setDetail(data)
        setLoading(false)
      })
      .catch((err) => {
        setLoading(false)
        if (isApiError(err)) {
          setRequestId(err.requestId)
          if (err.code === Code.FORBIDDEN) {
            setIs403(true)
            setErrorMsg('无权查看该预约')
          } else {
            setErrorMsg(err.message)
          }
        } else {
          setErrorMsg('获取预约详情失败')
        }
      })
  }, [rno])

  useEffect(() => {
    loadData()
  }, [loadData])

  const handleConfirmCancel = async () => {
    if (!rno || !detail || cancelBusy) return
    setCancelBusy(true)
    try {
      await reservationApi.cancel(rno, detail.version)
      toast.success('预约已成功取消')
      setCancelOpen(false)
      loadData()
    } catch (err) {
      if (isApiError(err)) {
        toast.error(err.message)
      } else {
        toast.error('取消预约失败')
      }
    } finally {
      setCancelBusy(false)
    }
  }

  if (loading) {
    return (
      <Card className="p-6 max-w-lg mx-auto space-y-4">
        <Skeleton className="h-6 w-32" />
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-10 w-full" />
      </Card>
    )
  }

  if (is403 || errorMsg) {
    return (
      <div className="max-w-lg mx-auto py-6">
        <ErrorState
          title={is403 ? '无权访问' : '查看详情失败'}
          message={errorMsg}
          requestId={requestId}
          onRetry={is403 ? undefined : loadData}
        />
        <div className="mt-4 text-center">
          <Button variant="outline" onClick={() => nav('/mine')}>
            <ArrowLeft className="h-4 w-4 mr-1.5" />
            返回我的预约
          </Button>
        </div>
      </div>
    )
  }

  if (!detail) return null

  const canQr = detail.status === 'CONFIRMED'
  const canCancel = detail.status === 'CONFIRMED' || detail.status === 'PENDING'
  const persisted = detail.status !== 'PENDING'

  return (
    <div className="mx-auto max-w-lg space-y-4">
      <PageHeader title="预约详情" description="查看预约状态与入园凭证" actions={<Button variant="outline" size="sm" onClick={() => nav(-1)}><ArrowLeft className="h-4 w-4" />返回</Button>} />

      <Card>
        <CardHeader className="border-b pb-4">
          <div className="flex items-center justify-between">
            <div className="space-y-1">
              <span className="font-mono text-xs text-muted-foreground">预约编号</span>
              <CardTitle className="break-all font-mono text-xl font-semibold">
                {detail.reservationNo}
              </CardTitle>
            </div>
            <ReservationStatusBadge status={detail.status} />
          </div>
        </CardHeader>

        <CardContent className="p-6 space-y-4">
          <div className="space-y-3 text-sm">
            <div className="flex items-center justify-between border-b pb-2">
              <span className="text-muted-foreground flex items-center gap-1.5">
                <Calendar className="h-4 w-4 text-primary" />
                游览日期
              </span>
              <span className="font-semibold text-foreground">{detail.slotDate}</span>
            </div>

            <div className="flex items-center justify-between border-b pb-2">
              <span className="text-muted-foreground flex items-center gap-1.5">
                <Clock className="h-4 w-4 text-primary" />
                场次时段
              </span>
              <span className="font-semibold text-foreground">
                {String(detail.slotHour).padStart(2, '0')}:00 时段
              </span>
            </div>

            <div className="flex items-center justify-between border-b pb-2">
              <span className="text-muted-foreground flex items-center gap-1.5">
                <ShieldCheck className="h-4 w-4 text-primary" />
                提交时间
              </span>
              <span className="font-mono">{detail.createAt}</span>
            </div>

            <div className="flex items-center justify-between border-b pb-2">
              <span className="text-muted-foreground">资源版本</span>
              <span className="font-mono">v{detail.version}</span>
            </div>

            {detail.verifyTime && (
              <div className="flex items-center justify-between border-b pb-2">
                <span className="text-muted-foreground">核销时间</span>
                <span className="font-mono font-semibold text-primary">{detail.verifyTime}</span>
              </div>
            )}
          </div>

          <div className="pt-2 space-y-2">
            <span className="text-xs font-semibold text-muted-foreground">状态进度</span>
            <div className="relative pl-6 space-y-4 border-l-2 border-primary/20 ml-2">
              <div className="relative">
                <div className="absolute -left-[31px] top-1 h-3 w-3 rounded-full bg-primary" />
                <div className="text-xs font-semibold text-foreground">预约已受理</div>
                <div className="text-[11px] text-muted-foreground font-mono">{detail.createAt}</div>
              </div>

              {detail.status === 'VERIFIED' && (
                <div className="relative">
                  <div className="absolute -left-[31px] top-1 h-3 w-3 rounded-full bg-primary" />
                  <div className="text-xs font-semibold text-primary">已到园核销</div>
                  <div className="text-[11px] text-muted-foreground font-mono">{detail.verifyTime || '完成核销'}</div>
                </div>
              )}

              {detail.status === 'CANCELLED' && (
                <div className="relative">
                  <div className="absolute -left-[31px] top-1 h-3 w-3 rounded-full bg-muted-foreground" />
                  <div className="text-xs font-semibold text-muted-foreground">已取消预约</div>
                </div>
              )}

              {detail.status === 'EXPIRED' && (
                <div className="relative">
                  <div className="absolute -left-[31px] top-1 h-3 w-3 rounded-full bg-amber-600" />
                  <div className="text-xs font-semibold text-amber-800">已过期</div>
                </div>
              )}

              {detail.status === 'REVIEW_REQUIRED' && (
                <div className="relative">
                  <div className="absolute -left-[31px] top-1 h-3 w-3 rounded-full bg-amber-600" />
                  <div className="text-xs font-semibold text-amber-800">等待人工核对</div>
                  <div className="text-[11px] text-muted-foreground">系统保留预约编号，运营人员正在处理一致性异常</div>
                </div>
              )}

              {detail.status === 'FAILED' && (
                <div className="relative">
                  <div className="absolute -left-[31px] top-1 h-3 w-3 rounded-full bg-destructive" />
                  <div className="text-xs font-semibold text-destructive">预约处理失败</div>
                  <div className="text-[11px] text-muted-foreground">该状态不会生成入园凭证</div>
                </div>
              )}
            </div>
          </div>

          <TechnicalTrace
            steps={[
              { label: 'Redis 原子预占', detail: '预约编号已生成，判重、扣减与预占在同一 Lua 脚本中完成。', state: 'done' },
              { label: '异步持久化', detail: persisted ? '预约详情已可从持久化记录读取。' : '消息链路正在完成分阶段落库。', state: persisted ? 'done' : 'active' },
              { label: '终态处理', detail: detail.status === 'VERIFIED' ? '核销 CAS 已完成。' : detail.status === 'CANCELLED' || detail.status === 'EXPIRED' || detail.status === 'FAILED' ? '预约已进入终态。' : '等待核销、取消或到期处理。', state: ['VERIFIED', 'CANCELLED', 'EXPIRED', 'FAILED'].includes(detail.status) ? 'done' : 'waiting' },
            ]}
          />
        </CardContent>

        <CardFooter className="flex flex-col gap-2.5 border-t bg-muted/20 p-6">
          {canQr && (
            <Button asChild className="w-full gap-2 font-semibold" size="lg">
              <Link to={`/reservation/${detail.reservationNo}/qr`}>
                <QrCode className="h-5 w-5" />
                <span>出示动态入园码</span>
              </Link>
            </Button>
          )}

          {detail.status === 'PENDING' && (
            <Button disabled className="w-full gap-2 font-semibold" size="lg">
              <QrCode className="h-5 w-5" />
              确认完成后可查看入园码
            </Button>
          )}

          {canCancel && (
            <Button
              variant="outline"
              onClick={() => setCancelOpen(true)}
              className="w-full gap-2 text-destructive border-destructive/30 hover:bg-destructive/10"
            >
              <XCircle className="h-4 w-4" />
              <span>取消该笔预约</span>
            </Button>
          )}
        </CardFooter>
      </Card>

      <AlertDialog
        open={cancelOpen}
        onOpenChange={setCancelOpen}
        title="确认取消预约？"
        variant="destructive"
        confirmText="确认作废预约"
        cancelText="暂不取消"
        busy={cancelBusy}
        onConfirm={handleConfirmCancel}
        description={
          <div className="space-y-2 text-sm text-foreground mt-2">
            <p>确定取消预约编号为 <strong className="font-mono">{rno}</strong> 的预约吗？</p>
            <div className="rounded border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive font-medium">
              名额不会返还，当天不能重新预约，此操作不可撤销。
            </div>
          </div>
        }
      />
    </div>
  )
}
