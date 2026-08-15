import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { QRCodeSVG } from 'qrcode.react'
import { RefreshCw, ArrowLeft, Copy, AlertTriangle, ShieldCheck, Sparkles } from 'lucide-react'
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

  const totalTtlRef = useRef<number>(60)

  const fetchQr = useCallback(async () => {
    if (!rno) return
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

  return (
    <div className="max-w-md mx-auto space-y-4 font-sans">
      <div className="flex items-center justify-between">
        <Button variant="ghost" size="sm" onClick={() => nav(-1)} aria-label="返回" className="rounded-lg text-slate-600">
          <ArrowLeft className="h-4 w-4 mr-1" />
          <span>返回</span>
        </Button>
        <span className="text-xs font-mono text-slate-500">RNO: {rno}</span>
      </div>

      <Card className="shadow-xl border border-slate-200/80 text-center rounded-2xl overflow-hidden bg-card">
        <CardHeader className="pb-3 pt-5 border-b border-slate-100 bg-slate-50/50">
          <div className="inline-flex items-center justify-center gap-1.5 text-xs font-semibold text-emerald-800 bg-emerald-50 border border-emerald-200/60 rounded-full px-3 py-1 mx-auto mb-1">
            <Sparkles className="h-3.5 w-3.5 text-emerald-600" />
            <span>动态安全入园凭证</span>
          </div>
          <CardTitle className="text-xl font-bold text-slate-900 font-serif">
            湿地公园准入二维码
          </CardTitle>
          {detail && (
            <CardDescription className="text-xs text-slate-500 font-mono mt-0.5">
              {detail.slotDate} · {String(detail.slotHour).padStart(2, '0')}:00 场次
            </CardDescription>
          )}
        </CardHeader>

        <CardContent className="p-6 flex flex-col items-center justify-center space-y-5">
          {loading ? (
            <div className="h-56 w-56 flex flex-col items-center justify-center bg-slate-100/60 rounded-2xl animate-pulse gap-2">
              <RefreshCw className="h-8 w-8 text-slate-400 animate-spin" />
              <span className="text-xs text-slate-400 font-mono">加载加密密钥…</span>
            </div>
          ) : errorMsg || !qrData ? (
            <div className="w-full py-6 space-y-4">
              <Alert variant="destructive" className="border-rose-200 bg-rose-50/70 text-rose-900">
                <AlertTriangle className="h-5 w-5" />
                <AlertDescription className="text-sm">
                  <div>{errorMsg || '二维码刷新失败'}</div>
                  {requestId && <RequestIdHint requestId={requestId} />}
                </AlertDescription>
              </Alert>
              <Button onClick={fetchQr} className="gap-2 bg-emerald-700 hover:bg-emerald-800 text-white rounded-lg">
                <RefreshCw className="h-4 w-4" />
                <span>重新获取二维码</span>
              </Button>
            </div>
          ) : (
            <>
              {/* High-Tech QR Scanner Frame with Camera Corner Brackets */}
              <div className="relative p-5 bg-white rounded-2xl shadow-inner border border-emerald-200/80 group">
                {/* Four Corner Brackets */}
                <div className="absolute top-2 left-2 w-4 h-4 border-t-2 border-l-2 border-emerald-600 rounded-tl-sm" />
                <div className="absolute top-2 right-2 w-4 h-4 border-t-2 border-r-2 border-emerald-600 rounded-tr-sm" />
                <div className="absolute bottom-2 left-2 w-4 h-4 border-b-2 border-l-2 border-emerald-600 rounded-bl-sm" />
                <div className="absolute bottom-2 right-2 w-4 h-4 border-b-2 border-r-2 border-emerald-600 rounded-br-sm" />

                {/* Laser Scan Line */}
                <div className="absolute left-3 right-3 h-[2px] bg-gradient-to-r from-transparent via-emerald-500 to-transparent pointer-events-none animate-scan-laser shadow-[0_0_8px_rgba(16,185,129,0.8)] z-10" />

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
                  <span className="text-slate-500">倒计时刷新</span>
                  <span className="font-bold text-emerald-800 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200/60">
                    {secondsLeft} 秒
                  </span>
                </div>
                <Progress value={progressPercent} max={100} className="h-2 rounded-full bg-slate-100" />
                <p className="text-[11px] text-slate-400 pt-0.5">
                  为防止截图作弊，防伪签名每 60 秒自动更新
                </p>
              </div>

              <div className="rounded-xl bg-slate-50/80 border border-slate-200/60 p-3 text-xs text-slate-600 flex items-center justify-center gap-2 w-full">
                <ShieldCheck className="h-4 w-4 text-emerald-700 shrink-0" />
                <span>现场核验请同步出示本人身份证原件</span>
              </div>
            </>
          )}
        </CardContent>

        {qrData && (
          <CardFooter className="flex flex-col gap-2 border-t border-slate-100 pt-4 bg-slate-50/50 text-xs">
            <Button
              variant="outline"
              size="sm"
              onClick={copyPayload}
              className="w-full gap-1.5 text-xs text-slate-600 rounded-lg border-slate-200"
            >
              <Copy className="h-3.5 w-3.5" />
              <span>复制原始载荷 (核验测试)</span>
            </Button>
          </CardFooter>
        )}
      </Card>
    </div>
  )
}
