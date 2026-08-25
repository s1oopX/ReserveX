import { useState, useEffect } from 'react'
import { useParams, Link } from 'react-router-dom'
import { CheckCircle2, CircleDashed, Database, QrCode, Ticket, RefreshCw, ServerCog, AlertTriangle, XCircle } from 'lucide-react'
import { reservationApi, type ReservationVO } from '@/api/reservation'
import { Code } from '@/api/codes'
import { isApiError } from '@/api/http'
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Skeleton } from '@/components/ui/skeleton'
import { getReservationResultState } from '@/lib/reservationResultState'

export default function ReservationResult() {
  const { rno } = useParams<{ rno: string }>()
  const [detail, setDetail] = useState<ReservationVO | null>(null)
  const [confirming, setConfirming] = useState<boolean>(false)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [retryNonce, setRetryNonce] = useState(0)

  useEffect(() => {
    if (!rno) return
    let isSubscribed = true
    let retryCount = 0

    const fetchDetail = () => {
      reservationApi
        .detail(rno)
        .then((data) => {
          if (!isSubscribed) return
          setDetail(data)
          setConfirming(false)
          setLoading(false)
        })
        .catch((err) => {
          if (!isSubscribed) return
          if (isApiError(err) && err.code === Code.RESERVATION_CONFIRMING) {
            setConfirming(true)
            if (retryCount < 5) {
              retryCount++
              setTimeout(fetchDetail, 1500)
            } else {
              setLoading(false)
            }
          } else {
            setLoading(false)
            setErrorMsg(isApiError(err) ? err.message : '获取预约详情失败')
          }
        })
    }

    fetchDetail()

    return () => {
      isSubscribed = false
    }
  }, [rno, retryNonce])

  const resultState = detail ? getReservationResultState(detail.status) : null
  const confirmed = resultState?.canShowQr === true
  const pending = resultState?.pending === true || confirming
  const terminalFailure = resultState?.terminalFailure === true
  const title = resultState?.title ?? (confirming ? '名额已预占' : '正在获取预约状态')
  const description = resultState?.description ?? '正在读取预约详情，请稍候。'
  const StatusIcon = confirmed ? CheckCircle2 : terminalFailure ? XCircle : pending ? CircleDashed : AlertTriangle

  return (
    <div className="mx-auto max-w-lg py-8">
      <Card className="overflow-hidden rounded-2xl border-slate-200 bg-white text-center shadow-[0_18px_45px_rgba(18,59,67,0.09)]">
        <CardHeader className="border-b border-slate-100 pb-6 pt-10">
          <div className={`mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-full ${confirmed ? 'bg-primary/10 text-primary' : terminalFailure ? 'bg-rose-50 text-rose-700' : 'bg-blue-50 text-blue-700'}`}>
            <StatusIcon className={`h-8 w-8 ${pending ? 'animate-spin' : ''}`} />
          </div>
          <CardTitle className="font-serif text-3xl font-semibold text-[#123b43]">
            {title}
          </CardTitle>
          <p className="text-sm text-muted-foreground mt-1">
            {description}
          </p>
        </CardHeader>

        <CardContent className="space-y-4 px-6 py-4">
          {loading ? (
            <div className="space-y-3">
              <Skeleton className="h-6 w-48 mx-auto" />
              <Skeleton className="h-4 w-36 mx-auto" />
            </div>
          ) : errorMsg ? (
            <Alert variant="destructive">
              <AlertDescription>{errorMsg}</AlertDescription>
            </Alert>
          ) : (
            <div className="space-y-2.5 rounded-xl border border-slate-200 bg-slate-50/70 p-5 text-left font-mono text-sm">
              <div className="flex justify-between border-b pb-2">
                <span className="text-muted-foreground font-sans">预约编号:</span>
                <span className="font-bold text-foreground">{rno}</span>
              </div>
              {detail && (
                <>
                  <div className="flex justify-between pt-1">
                    <span className="text-muted-foreground font-sans">游览日期:</span>
                    <span className="text-foreground">{detail.slotDate}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground font-sans">场次时段:</span>
                    <span className="text-foreground">{String(detail.slotHour).padStart(2, '0')}:00 时段</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground font-sans">当前状态:</span>
                    <span className={`font-sans font-semibold ${confirmed ? 'text-primary' : terminalFailure ? 'text-destructive' : 'text-blue-700'}`}>{resultState?.statusLabel ?? '读取中'}</span>
                  </div>
                </>
              )}
            </div>
          )}

          {!errorMsg && (
            <div className="rounded-xl border bg-muted/20 p-4 text-left">
              <div className="mb-3 text-xs font-semibold text-foreground">本次处理状态</div>
              <div className="space-y-2.5 text-xs">
                <div className="flex items-center justify-between gap-3"><span className="flex items-center gap-2 text-muted-foreground"><ServerCog className="h-4 w-4 text-primary" />Redis 原子预占</span><span className="font-medium text-primary">已完成</span></div>
                <div className="flex items-center justify-between gap-3"><span className="flex items-center gap-2 text-muted-foreground"><RefreshCw className={`h-4 w-4 text-blue-600 ${pending ? 'animate-spin' : ''}`} />异步确认</span><span className={confirmed ? 'font-medium text-primary' : pending ? 'font-medium text-blue-700' : 'font-medium text-muted-foreground'}>{confirmed ? '已完成' : pending ? '处理中' : '未继续'}</span></div>
                <div className="flex items-center justify-between gap-3"><span className="flex items-center gap-2 text-muted-foreground"><Database className="h-4 w-4 text-indigo-600" />预约详情可读取</span><span className={confirmed ? 'font-medium text-primary' : terminalFailure ? 'font-medium text-destructive' : 'font-medium text-muted-foreground'}>{confirmed ? '可用' : terminalFailure ? '已终止' : '等待中'}</span></div>
              </div>
            </div>
          )}

          {confirming && !loading && (
            <Button variant="outline" size="sm" onClick={() => { setLoading(true); setRetryNonce((value) => value + 1) }} className="gap-2">
              <RefreshCw className="h-4 w-4" />
              再次查询确认状态
            </Button>
          )}
        </CardContent>

        <CardFooter className="flex flex-col gap-2.5 border-t border-slate-100 bg-slate-50/60 p-6">
          {rno && confirmed && (
            <Button asChild className="w-full gap-2 font-semibold" size="lg">
              <Link to={`/reservation/${rno}/qr`}>
                <QrCode className="h-5 w-5" />
                <span>查看出示入园码</span>
              </Link>
            </Button>
          )}
          {pending && (
            <Button disabled className="w-full gap-2 font-semibold" size="lg">
              <QrCode className="h-5 w-5" />
              确认完成后可查看入园码
            </Button>
          )}
          <Button asChild variant="outline" className="w-full gap-2">
            <Link to="/mine">
              <Ticket className="h-4 w-4" />
              <span>查看我的预约列表</span>
            </Link>
          </Button>
        </CardFooter>
      </Card>
    </div>
  )
}
