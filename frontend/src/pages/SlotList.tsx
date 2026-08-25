import { useState, useEffect, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { Calendar, ChevronRight, Clock, Info, ShieldAlert, RefreshCw, Zap } from 'lucide-react'
import { reservationApi, type SlotVO } from '@/api/reservation'
import { captchaApi, type CaptchaVO } from '@/api/captcha'
import { isApiError } from '@/api/http'
import { Code } from '@/api/codes'
import { todayInZone, addDaysInZone, nowInZone, formatEpochSeconds } from '@/lib/datetime'

import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter } from '@/components/ui/sheet'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Skeleton } from '@/components/ui/skeleton'
import { SlotStatusBadge } from '@/components/common/StatusBadge'
import { ErrorState } from '@/components/common/ErrorState'
import { RequestIdHint } from '@/components/common/RequestIdHint'
import { PageHeader } from '@/components/common/PageHeader'
import { toast } from '@/components/ui/sonner'
import { getSlotUiStatus, type SlotUiStatus } from '@/lib/slotStatus'

type SlotViewFilter = 'all' | 'available' | 'unreleased' | 'full' | 'ended'

export default function SlotList() {
  const nav = useNavigate()
  const minDate = todayInZone()
  const defaultDate = addDaysInZone(minDate, 1)

  const [date, setDate] = useState<string>(defaultDate)
  const [slots, setSlots] = useState<SlotVO[] | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')

  const reqIdRef = useRef<number>(0)

  const [selectedSlot, setSelectedSlot] = useState<SlotVO | null>(null)
  const [openModal, setOpenModal] = useState<boolean>(false)
  const [grabBusy, setGrabBusy] = useState<boolean>(false)
  const [grabError, setGrabError] = useState<string>('')
  const [grabRequestId, setGrabRequestId] = useState<string>('')
  // D4 风控验证码:后端返 CAPTCHA_REQUIRED 时拉取验证码图片并要求用户输入。
  const [captcha, setCaptcha] = useState<CaptchaVO | null>(null)
  const [captchaInput, setCaptchaInput] = useState<string>('')
  const [captchaBusy, setCaptchaBusy] = useState<boolean>(false)
  const [viewFilter, setViewFilter] = useState<SlotViewFilter>('all')

  const [isMobile, setIsMobile] = useState<boolean>(false)

  // Generate 7-day pill dates array
  const datePills = Array.from({ length: 7 }, (_, i) => {
    const d = addDaysInZone(minDate, i)
    const dateObj = new Date(d)
    const days = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
    const dayLabel = i === 0 ? '今天' : i === 1 ? '明天' : days[dateObj.getDay()]
    return { dateStr: d, label: dayLabel, shortDate: d.slice(5) }
  })

  useEffect(() => {
    const checkMobile = () => setIsMobile(window.innerWidth < 768)
    checkMobile()
    window.addEventListener('resize', checkMobile)
    return () => window.removeEventListener('resize', checkMobile)
  }, [])

  const fetchSlots = useCallback(() => {
    const currentReqId = ++reqIdRef.current

    setSlots(null)
    setLoading(true)
    setErrorMsg('')
    setRequestId('')

    reservationApi
      .listSlots(date)
      .then((data) => {
        if (currentReqId === reqIdRef.current) {
          setSlots(data)
          setLoading(false)
        }
      })
      .catch((err) => {
        if (currentReqId === reqIdRef.current) {
          setLoading(false)
          if (isApiError(err)) {
            setErrorMsg(err.message)
            setRequestId(err.requestId)
          } else {
            setErrorMsg('网络繁忙，无法加载场次列表，请重试')
          }
        }
      })
  }, [date])

  useEffect(() => {
    fetchSlots()
  }, [fetchSlots])

  const handleOpenConfirm = (slot: SlotVO) => {
    setSelectedSlot(slot)
    setGrabError('')
    setGrabRequestId('')
    setCaptcha(null)
    setCaptchaInput('')
    setOpenModal(true)
  }

  const fetchCaptcha = useCallback(async () => {
    setCaptchaBusy(true)
    try {
      const data = await captchaApi.create()
      setCaptcha(data)
      setCaptchaInput('')
    } catch {
      toast.error('验证码获取失败,请稍后重试')
    } finally {
      setCaptchaBusy(false)
    }
  }, [])

  const handleConfirmGrab = async () => {
    if (!selectedSlot || grabBusy) return
    setGrabBusy(true)
    setGrabError('')
    setGrabRequestId('')

    // 若风控要求验证码且用户尚未输入,提示并拉取验证码
    if (captcha && !captchaInput.trim()) {
      setGrabError('请输入图形验证码')
      setGrabBusy(false)
      return
    }
    const captchaToken = captcha && captchaInput.trim() ? `${captcha.key}:${captchaInput.trim()}` : undefined

    try {
      const res = await reservationApi.grab(selectedSlot.slotId, captchaToken)
      toast.success('名额抢占成功！正在确认预约信息…')
      setOpenModal(false)
      nav(`/reservation/${res.reservationNo}/result`)
    } catch (err) {
      if (isApiError(err)) {
        setGrabRequestId(err.requestId)
        if (err.code === Code.SLOT_FULL) {
          setGrabError('手慢了，该场次游览名额已被抢光')
        } else if (err.code === Code.QUOTA_USED) {
          setGrabError('您今天已存在预约，每日限预约 1 次')
        } else if (err.code === Code.RATE_LIMITED || err.code === Code.SERVICE_DEGRADED) {
          setGrabError('系统繁忙，请稍后再试（风控限流）')
        } else if (err.code === Code.CAPTCHA_REQUIRED) {
          // 风控触发:拉取验证码,要求用户输入后重试
          setGrabError('为保障公平,请先完成图形验证后再次提交')
          if (!captcha) await fetchCaptcha()
        } else if (err.code === Code.CAPTCHA_INVALID) {
          setGrabError('验证码错误,请重新输入')
          await fetchCaptcha()
        } else {
          setGrabError(err.message)
        }
      } else {
        setGrabError('提交异常，请稍后重试')
      }
    } finally {
      setGrabBusy(false)
    }
  }

  const nowStr = nowInZone()
  const filteredSlots = slots?.filter((slot) => viewFilter === 'all' || getSlotUiStatus(slot, nowStr) === viewFilter)

  const modalFormContent = (
    <div className="space-y-4 py-2">
      {selectedSlot && (
        <div className="space-y-2.5 rounded-md border bg-muted/30 p-4 text-xs">
          <div className="flex items-center justify-between border-b pb-2">
            <span className="text-muted-foreground">游览日期</span>
            <span className="font-mono text-sm font-semibold text-primary">{selectedSlot.slotDate}</span>
          </div>
          <div className="flex justify-between items-center">
            <span className="text-muted-foreground">场次时段</span>
            <span className="font-mono text-sm font-semibold">{String(selectedSlot.slotHour).padStart(2, '0')}:00 场次</span>
          </div>
          <div className="flex justify-between items-center">
            <span className="text-muted-foreground">建议游览时长</span>
            <span className="font-medium">{selectedSlot.durationMin} 分钟</span>
          </div>
        </div>
      )}

      <Alert variant="warning">
        <ShieldAlert className="h-4 w-4 text-amber-700 shrink-0" />
        <AlertDescription className="text-xs text-amber-900 font-medium leading-relaxed">
          <strong>重要告知：</strong> 成功预约后不可改签；放弃预约名额<strong>不可恢复且无法退还名额池</strong>。
        </AlertDescription>
      </Alert>

      {/* D4 风控验证码:仅在后端要求时出现 */}
      {captcha && (
        <div className="flex items-center gap-3 rounded-md border bg-background p-3">
          <img
            src={captcha.imageBase64}
            alt="验证码"
            className="h-10 rounded border border-slate-200 cursor-pointer"
            onClick={fetchCaptcha}
            title="点击刷新"
          />
          <input
            value={captchaInput}
            onChange={(e) => setCaptchaInput(e.target.value)}
            placeholder="输入图中字符"
            className="flex-1 rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-ring"
            disabled={captchaBusy}
            maxLength={8}
          />
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={fetchCaptcha}
            disabled={captchaBusy}
            className="text-xs text-slate-500"
          >
            换一张
          </Button>
        </div>
      )}

      {grabError && (
        <Alert variant="destructive">
          <AlertDescription className="text-xs">
            <div>{grabError}</div>
            {grabRequestId && <RequestIdHint requestId={grabRequestId} />}
          </AlertDescription>
        </Alert>
      )}
    </div>
  )

  return (
    <div className="space-y-6">
      <PageHeader title="场次预约" description="每日 10:00 开放次日预约名额 · 一证一天单次有效" actions={<Button variant="outline" size="sm" onClick={fetchSlots} aria-label="刷新场次"><RefreshCw className="h-4 w-4" />刷新</Button>} />

      {/* Responsive Interactive Date Capsule Switcher */}
      <div className="space-y-2">
        <div className="flex items-center justify-between text-xs font-medium text-muted-foreground">
          <span className="flex items-center gap-1.5">
            <Calendar className="h-4 w-4 text-primary" />
            <span>选择游览日期 (未来 7 天)</span>
          </span>
          <span className="font-mono text-[11px]">当前选中：{date}</span>
        </div>

        <div className="flex items-center gap-2 overflow-x-auto pb-1 scrollbar-none">
          {datePills.map((pill) => {
            const isSelected = pill.dateStr === date
            return (
              <button
                key={pill.dateStr}
                type="button"
                onClick={() => setDate(pill.dateStr)}
                className={`flex min-w-[78px] shrink-0 flex-col items-center justify-center rounded-xl border px-3.5 py-3 text-xs transition-colors ${
                  isSelected
                    ? 'border-primary bg-primary font-semibold text-primary-foreground'
                    : 'bg-card text-muted-foreground hover:border-primary/40 hover:bg-muted/40'
                }`}
              >
                <span className="text-[11px] opacity-90">{pill.label}</span>
                <span className="font-mono font-bold text-xs mt-0.5">{pill.shortDate}</span>
              </button>
            )
          })}
        </div>

        <div className="flex flex-wrap gap-2" aria-label="场次状态筛选">
          {([
            ['all', '全部场次'],
            ['available', '可预约'],
            ['unreleased', '未放号'],
            ['full', '已满'],
            ['ended', '已结束'],
          ] as const).map(([value, label]) => (
            <button
              key={value}
              type="button"
              aria-pressed={viewFilter === value}
              onClick={() => setViewFilter(value)}
              className={`rounded-md border px-3 py-1.5 text-xs font-medium transition-colors ${viewFilter === value ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-background text-muted-foreground hover:border-primary/40 hover:text-foreground'}`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      <div className="flex items-start gap-3 rounded-xl border border-primary/15 bg-[#eef8f4] p-4 text-sm">
        <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
        <div><div className="font-medium">实名预约与现场核验</div><p className="mt-1 text-xs text-muted-foreground">提交后先由 Redis 原子预占名额，再通过消息异步确认；入园请出示本人身份证原件与动态 60 秒二维码。</p></div>
      </div>

      {/* Loading Skeletons */}
      {loading && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <Card key={i} className="space-y-3 p-5">
              <Skeleton className="h-6 w-3/4" />
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-10 w-full mt-4" />
            </Card>
          ))}
        </div>
      )}

      {/* Error State */}
      {errorMsg && (
        <ErrorState
          title="无法获取场次列表"
          message={errorMsg}
          requestId={requestId}
          onRetry={fetchSlots}
        />
      )}

      {/* Slot List Grid */}
      {!loading && !errorMsg && slots && (
        filteredSlots?.length === 0 ? (
          <Card className="border-dashed p-12 text-center text-muted-foreground">
            <Info className="h-8 w-8 mx-auto mb-2 text-slate-400" />
            <p className="text-sm font-medium text-slate-700">没有符合当前筛选的场次</p>
            <p className="text-xs mt-1 text-slate-400">可切换日期或选择“全部场次”查看。</p>
          </Card>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {filteredSlots?.map((slot) => {
              const statusType: SlotUiStatus = getSlotUiStatus(slot, nowStr)
              const releaseAtNum = Number(slot.releaseAt) || 0
              const formattedReleaseAt = releaseAtNum > 0 ? formatEpochSeconds(releaseAtNum) : '未定'

              return (
                <Card
                  key={slot.slotId}
                  className="relative flex flex-col justify-between overflow-hidden rounded-2xl border-slate-200 bg-white shadow-sm transition-shadow hover:shadow-md"
                >
                  <CardHeader className="pb-3 pt-4">
                    <div className="flex items-center justify-between">
                      <CardTitle className="flex items-center gap-1.5 font-serif text-xl font-semibold text-[#123b43]">
                        <Clock className="h-4 w-4 text-primary" />
                        <span>{String(slot.slotHour).padStart(2, '0')}:00 场次</span>
                      </CardTitle>
                      <SlotStatusBadge status={statusType} />
                    </div>
                    <CardDescription className="mt-1 font-mono text-xs">
                      时长: {slot.durationMin} 分钟 · 编号 #{slot.slotId}
                    </CardDescription>
                  </CardHeader>

                  <CardContent className="pb-4 space-y-2.5">
                    <div className="flex justify-between items-center text-xs">
                      <span className="text-slate-500">放号时点</span>
                        <span className="font-mono text-slate-700">{formattedReleaseAt}</span>
                    </div>

                    <div className="flex justify-between items-center text-xs">
                      <span className="text-muted-foreground">当前余量</span>
                      <span className="font-mono text-sm font-semibold text-primary">
                        {statusType === 'unreleased' ? '未放号' : `${slot.remain} 人`}
                      </span>
                    </div>

                  </CardContent>

                  <CardFooter className="border-t border-slate-100 bg-slate-50/60 pt-3">
                    {statusType === 'ended' ? (
                      <Button disabled variant="outline" className="w-full text-xs text-slate-400">
                        预约已结束
                      </Button>
                    ) : statusType === 'unreleased' ? (
                      <Button disabled variant="outline" className="w-full text-xs">
                        等待开放
                      </Button>
                    ) : statusType === 'full' ? (
                      <Button disabled variant="outline" className="w-full border-rose-200 bg-rose-50/50 text-xs text-rose-600">
                        名额已满
                      </Button>
                    ) : (
                      <Button
                        onClick={() => handleOpenConfirm(slot)}
                        className="w-full gap-1.5 font-semibold"
                      >
                        <Zap className="h-3.5 w-3.5" />
                        <span>立即预约</span>
                        <ChevronRight className="h-4 w-4" />
                      </Button>
                    )}
                  </CardFooter>
                </Card>
              )
            })}
          </div>
        )
      )}

      {/* Confirmation Modal */}
      {isMobile ? (
        <Sheet open={openModal} onOpenChange={setOpenModal} side="bottom">
          <SheetContent className="p-6">
            <SheetHeader>
            <SheetTitle className="text-lg font-semibold">确认游览预约信息</SheetTitle>
              <SheetDescription className="text-xs">请核对您的预约时段与注意事项。</SheetDescription>
            </SheetHeader>
            {modalFormContent}
            <SheetFooter className="gap-2 pt-4">
              <Button variant="outline" disabled={grabBusy} onClick={() => setOpenModal(false)} className="flex-1">
                取消
              </Button>
              <Button disabled={grabBusy} onClick={handleConfirmGrab} className="flex-1 font-semibold">
                {grabBusy ? '抢占名额中…' : '确认抢占预约名额'}
              </Button>
            </SheetFooter>
          </SheetContent>
        </Sheet>
      ) : (
        <Dialog open={openModal} onOpenChange={setOpenModal}>
          <DialogContent className="max-w-md rounded-2xl p-6">
            <DialogHeader>
              <DialogTitle className="text-lg font-semibold">确认游览预约信息</DialogTitle>
              <DialogDescription className="text-xs">请核对您的预约时段与注意事项。</DialogDescription>
            </DialogHeader>
            {modalFormContent}
            <DialogFooter className="gap-3 pt-2">
              <Button variant="outline" disabled={grabBusy} onClick={() => setOpenModal(false)}>
                取消
              </Button>
              <Button disabled={grabBusy} onClick={handleConfirmGrab} className="font-semibold">
                {grabBusy ? '抢占名额中…' : '确认抢占预约名额'}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  )
}
