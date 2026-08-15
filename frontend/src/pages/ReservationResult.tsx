import { useState, useEffect } from 'react'
import { useParams, Link } from 'react-router-dom'
import { CheckCircle2, QrCode, Ticket, RefreshCw } from 'lucide-react'
import { reservationApi, type ReservationVO } from '@/api/reservation'
import { Code } from '@/api/codes'
import { isApiError } from '@/api/http'
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Skeleton } from '@/components/ui/skeleton'

export default function ReservationResult() {
  const { rno } = useParams<{ rno: string }>()
  const [detail, setDetail] = useState<ReservationVO | null>(null)
  const [confirming, setConfirming] = useState<boolean>(false)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')

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
  }, [rno])

  return (
    <div className="max-w-md mx-auto py-6">
      <Card className="shadow-lg border-border text-center">
        <CardHeader className="pt-8 pb-4">
          <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100 text-emerald-700 mb-3">
            <CheckCircle2 className="h-10 w-10 text-emerald-600" />
          </div>
          <CardTitle className="text-2xl font-bold text-foreground font-serif">
            {confirming ? '预约正在确认' : '预约已受理'}
          </CardTitle>
          <p className="text-sm text-muted-foreground mt-1">
            {confirming ? '系统正在处理您的预约配额，请稍候…' : '凭证生成成功，请凭入园二维码准时入园游览。'}
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
            <div className="rounded-lg bg-muted/40 p-4 space-y-2 text-sm text-left font-mono">
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
                    <span className="text-emerald-700 font-sans font-semibold">预约成功</span>
                  </div>
                </>
              )}
            </div>
          )}

          {confirming && (
            <div className="flex items-center justify-center gap-2 text-xs text-amber-700 py-2">
              <RefreshCw className="h-3.5 w-3.5 animate-spin" />
              <span>确认中，页面正在自动刷新…</span>
            </div>
          )}
        </CardContent>

        <CardFooter className="flex flex-col gap-2.5 p-6 border-t">
          {rno && (
            <Button asChild className="w-full gap-2 font-semibold" size="lg">
              <Link to={`/reservation/${rno}/qr`}>
                <QrCode className="h-5 w-5" />
                <span>查看出示入园码</span>
              </Link>
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
