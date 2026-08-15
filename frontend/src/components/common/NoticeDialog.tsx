import { useState, useEffect, useRef, useCallback } from 'react'
import { NoticeContent } from './NoticeContent'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'

interface NoticeDialogProps {
  open: boolean
  onDecline: () => void
  onAccept: () => void
}

export function NoticeDialog({ open, onDecline, onAccept }: NoticeDialogProps) {
  const [secondsRead, setSecondsRead] = useState<number>(0)
  const [isBottomReached, setIsBottomReached] = useState<boolean>(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  const checkScroll = useCallback(() => {
    if (!scrollRef.current) return
    const { scrollTop, scrollHeight, clientHeight } = scrollRef.current
    if (scrollHeight <= clientHeight + 8 || scrollTop + clientHeight >= scrollHeight - 8) {
      setIsBottomReached(true)
    }
  }, [])

  useEffect(() => {
    if (open) {
      setSecondsRead(0)
      setIsBottomReached(false)

      const timer = setTimeout(() => {
        checkScroll()
      }, 100)

      return () => clearTimeout(timer)
    }
  }, [open, checkScroll])

  useEffect(() => {
    if (!open || secondsRead >= 10) return

    let intervalId: number | null = null

    const startTimer = () => {
      if (document.visibilityState === 'visible' && !intervalId) {
        intervalId = window.setInterval(() => {
          setSecondsRead((prev) => {
            if (prev >= 10) {
              if (intervalId) clearInterval(intervalId)
              return 10
            }
            return prev + 1
          })
        }, 1000)
      }
    }

    const stopTimer = () => {
      if (intervalId) {
        clearInterval(intervalId)
        intervalId = null
      }
    }

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden') {
        stopTimer()
      } else {
        startTimer()
      }
    }

    startTimer()
    document.addEventListener('visibilitychange', handleVisibilityChange)

    return () => {
      stopTimer()
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [open, secondsRead])

  const isTimeMet = secondsRead >= 10
  const isScrollMet = isBottomReached
  const canAgree = isTimeMet && isScrollMet

  const secondsLeft = Math.max(0, 10 - secondsRead)

  const getStatusMessage = () => {
    if (canAgree) {
      return '阅读条件已满足，请确认'
    }
    if (!isTimeMet && !isScrollMet) {
      return `还需阅读 ${secondsLeft} 秒，且请滑动至底部`
    }
    if (!isTimeMet) {
      return `还需阅读 ${secondsLeft} 秒`
    }
    return '请滑动至底部'
  }

  return (
    <Dialog open={open} onOpenChange={(isOpen) => { if (!isOpen) onDecline() }}>
      <DialogContent className="flex max-h-[85vh] sm:max-h-[90vh] flex-col max-w-xl p-0 overflow-hidden shadow-2xl border">
        <DialogHeader className="px-6 pt-5 pb-3 border-b shrink-0 bg-muted/20">
          <DialogTitle className="text-lg font-bold font-serif text-foreground">
            湿地公园预约须知与规则
          </DialogTitle>
          <DialogDescription className="text-xs mt-1 text-muted-foreground">
            必须满足“累计阅读满 10 秒”且“已滚动到底部”后方可完成协议确认。
          </DialogDescription>
        </DialogHeader>

        <div
          ref={scrollRef}
          onScroll={checkScroll}
          tabIndex={0}
          role="region"
          aria-label="预约须知与规则条款正文"
          className="flex-1 overflow-y-auto px-6 py-5 focus:outline-none focus-visible:outline-none select-text"
        >
          <NoticeContent />
        </div>

        <DialogFooter className="px-6 py-4 border-t bg-card shrink-0 flex flex-col items-center justify-center gap-2 sm:flex-col sm:space-x-0">
          <p
            className="text-center text-xs font-semibold font-mono text-muted-foreground w-full"
            aria-live="polite"
          >
            {getStatusMessage()}
          </p>

          <div className="flex items-center justify-center gap-3 w-full">
            <Button
              type="button"
              variant="outline"
              onClick={onDecline}
              className="h-10 px-5 text-xs font-medium shrink-0"
            >
              暂不同意
            </Button>

            <Button
              type="button"
              variant={canAgree ? 'destructive' : 'outline'}
              disabled={!canAgree}
              onClick={onAccept}
              className="h-10 px-5 text-xs font-semibold whitespace-nowrap shrink-0 disabled:opacity-60 transition-all"
            >
              我已知晓，同意并遵守该须知
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
