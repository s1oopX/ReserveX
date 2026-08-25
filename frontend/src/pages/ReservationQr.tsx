import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { QRCodeSVG } from 'qrcode.react'
import { RefreshCw, ArrowLeft, Copy, AlertTriangle, ShieldCheck, KeyRound } from 'lucide-react'
import { reservationApi, type QrVO, type ReservationVO } from '@/api/reservation'
import { isApiError } from '@/api/http'
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { toast } from '@/components/ui/sonner'
import { RequestIdHint } from '@/components/common/RequestIdHint'

export default function ReservationQr() {
  const { rno } = useParams<{ rno: string }>()
  const nav = useNavigate()

  const [qrData, setQrData] = useState<QrVO | null>(null)
  const [detail, setDetail] = useState<ReservationVO | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')
  const [secondsLeft, setSecondsLeft] = useState<number>(0)
  const [refreshing, setRefreshing] = useState<boolean>(false)

  const totalTtlRef = useRef<number>(60)

  const fetchQr = useCallback(async () => {
    if (!rno) return
    setRefreshing(true)
    setErrorMsg('')
    setRequestId('')
    try {
      const [qrRes, detailRes] = await Promise.all([
        reservationApi.qr(rno),
        reservationApi.detail(rno).catch(() => null),
      ])

      const expNum = Number(qrRes.exp) || 0
      const nowSec = Math.floor(Date.now() / 1000)
      const left = Math.max(0, expNum - nowSec)

      if (left <= 0) {
        setQrData(null)
        setErrorMsg('二维码已过期，请点击重新获取')
        return
      }

      totalTtlRef.current = Math.max(left, 60)
      setSecondsLeft(left)
      setQrData(qrRes)
      if (detailRes) setDetail(detailRes)
    } catch (err) {
      setQrData(null)
      if (isApiError(err)) {
        setErrorMsg(err.message)
        setRequestId(err.requestId)
      } else {
        setErrorMsg('二维码刷新失败，请重新尝试')
      }
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [rno])

  useEffect(() => {
    fetchQr()
  }, [fetchQr])

  useEffect(() => {
    if (!qrData) return
    const interval = setInterval(() => {
      const expNum = Number(qrData.exp) || 0
      const nowSec = Math.floor(Date.now() / 1000)
      const left = Math.max(0, expNum - nowSec)
      setSecondsLeft(left)

      if (left <= 2) {
        clearInterval(interval)
        fetchQr()
      }
    }, 1000)

    return () => clearInterval(interval)
  }, [qrData, fetchQr])

  useEffect(() => {
    const handleVisibility = () => {
      if (document.visibilityState === 'visible') {
        fetchQr()
      }
    }
    document.addEventListener('visibilitychange', handleVisibility)
    return () => document.removeEventListener('visibilitychange', handleVisibility)
  }, [fetchQr])

  const copyPayload = () => {
    if (!qrData?.payload) return
    navigator.clipboard?.writeText(qrData.payload).then(
      () => toast.success('已复制原始 QR 载荷'),
      () => toast.error('复制失败，请手动复制')
    )
  }

  const progressPercent = Math.min(100, Math.max(0, (secondsLeft / totalTtlRef.current) * 100))
  const payloadParts = qrData?.payload.split('.') ?? []
  const [payloadVersion, keyId, , expiresAt, nonce] = payloadParts

  return (
    <div className="mx-auto max-w-md space-y-4 font-sans">
      <div className="flex items-center justify-between">
        <Button variant="ghost" size="sm" onClick={() => nav(-1)} aria-label="返回">
          <ArrowLeft className="h-4 w-4 mr-1" />
          <span>返回</span>
        </Button>
        <span className="text-xs font-mono text-slate-500">RNO: {rno}</span>
      </div>

      <Card className="overflow-hidden text-center">
        <CardHeader className="border-b pb-4 pt-5">
          <div className="mx-auto mb-2 inline-flex items-center justify-center gap-1.5 rounded-md border border-primary/20 bg-primary/5 px-3 py-1 text-xs font-medium text-primary">
            <ShieldCheck className="h-3.5 w-3.5" />
            <span>动态安全入园凭证</span>
          </div>
          <CardTitle className="text-xl font-semibold">
            动态入园凭证
          </CardTitle>
          {detail && (
            <CardDescription className="text-xs text-slate-500 font-mono mt-0.5">
              {detail.slotDate} · {String(detail.slotHour).padStart(2, '0')}:00 场次
            </CardDescription>
          )}
        </CardHeader>

        <CardContent className="p-6 flex flex-col items-center justify-center space-y-5">
          {loading ? (
            <div className="flex h-56 w-56 flex-col items-center justify-center gap-2 rounded-md bg-muted/60">
              <RefreshCw className="h-8 w-8 animate-spin text-muted-foreground" />
              <span className="font-mono text-xs text-muted-foreground">加载加密密钥…</span>
            </div>
          ) : errorMsg || !qrData ? (
            <div className="w-full py-6 space-y-4">
                <Alert variant="destructive">
                <AlertTriangle className="h-5 w-5" />
                <AlertDescription className="text-sm">
                  <div>{errorMsg || '二维码刷新失败'}</div>
                  {requestId && <RequestIdHint requestId={requestId} />}
                </AlertDescription>
              </Alert>
              <Button onClick={fetchQr} className="gap-2">
                <RefreshCw className="h-4 w-4" />
                <span>重新获取二维码</span>
              </Button>
            </div>
          ) : (
            <>
              <div className="relative rounded-xl border bg-white p-5 shadow-inner">
                <div className="absolute left-2 top-2 h-4 w-4 border-l-2 border-t-2 border-primary" />
                <div className="absolute right-2 top-2 h-4 w-4 border-r-2 border-t-2 border-primary" />
                <div className="absolute bottom-2 left-2 h-4 w-4 border-b-2 border-l-2 border-primary" />
                <div className="absolute bottom-2 right-2 h-4 w-4 border-b-2 border-r-2 border-primary" />

                <QRCodeSVG
                  value={qrData.payload}
                  size={200}
                  level="M"
                  includeMargin={false}
                />
              </div>

              {/* Progress Bar & Countdown */}
              <div className="w-full max-w-xs space-y-2 pt-1">
                <div className="flex justify-between items-center text-xs font-mono">
                  <span className="text-muted-foreground">倒计时刷新</span>
                  <span className="rounded border border-primary/20 bg-primary/5 px-2 py-0.5 font-bold text-primary">
                    {secondsLeft} 秒
                  </span>
                </div>
                <Progress value={progressPercent} max={100} className="h-2" />
                <p className="pt-0.5 text-[11px] text-muted-foreground">
                  凭证采用 HMAC 签名并按有效期自动更新；重复核销由服务端状态 CAS 拦截。
                </p>
              </div>

              <Button variant="outline" size="sm" onClick={fetchQr} disabled={refreshing} className="w-full max-w-xs gap-2">
                <RefreshCw className={`h-4 w-4 ${refreshing ? 'animate-spin' : ''}`} />
                {refreshing ? '正在刷新凭证…' : '立即刷新凭证'}
              </Button>

              <div className="flex w-full items-center justify-center gap-2 rounded-md border bg-muted/30 p-3 text-xs text-muted-foreground">
                <ShieldCheck className="h-4 w-4 shrink-0 text-primary" />
                <span>现场核验请同步出示本人身份证原件</span>
              </div>
            </>
          )}
        </CardContent>

        {qrData && (
          <CardFooter className="flex flex-col gap-2 border-t pt-4 text-xs">
            <details className="w-full rounded-lg border bg-muted/20 text-left">
              <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2.5 font-medium text-foreground">
                <KeyRound className="h-4 w-4 text-primary" />
                开发演示：查看验签信息
              </summary>
              <div className="space-y-2 border-t px-3 py-3 font-mono text-[11px] text-muted-foreground">
                <div className="flex justify-between gap-4"><span>payload version</span><span className="text-foreground">{payloadVersion || '-'}</span></div>
                <div className="flex justify-between gap-4"><span>key id</span><span className="text-foreground">{keyId || '-'}</span></div>
                <div className="flex justify-between gap-4"><span>expires at</span><span className="text-foreground">{expiresAt || '-'}</span></div>
                <div className="flex justify-between gap-4"><span>nonce</span><span className="max-w-40 truncate text-foreground">{nonce || '-'}</span></div>
                <Button variant="outline" size="sm" onClick={copyPayload} className="mt-2 w-full gap-1.5 text-xs">
                  <Copy className="h-3.5 w-3.5" />
                  复制原始载荷
                </Button>
              </div>
            </details>
          </CardFooter>
        )}
      </Card>
    </div>
  )
}
