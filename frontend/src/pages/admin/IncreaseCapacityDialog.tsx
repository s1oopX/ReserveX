import { useState } from 'react'
import { adminApi, type SlotDetail } from '@/api/admin'
import { isApiError } from '@/api/http'
import { Dialog, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { toast } from '@/components/ui/sonner'

export function IncreaseCapacityDialog({
  slot,
  onClose,
  onDone,
}: {
  slot: SlotDetail
  onClose: () => void
  onDone: () => void
}) {
  const [delta, setDelta] = useState<string>('')
  const [submitting, setSubmitting] = useState<boolean>(false)
  const [errorMsg, setErrorMsg] = useState<string>('')

  const submit = () => {
    const n = Number(delta)
    if (!Number.isInteger(n) || n <= 0) {
      setErrorMsg('增容数量必须为正整数')
      return
    }
    setSubmitting(true)
    setErrorMsg('')
    adminApi
      .increaseCapacity(slot.slotId, slot.capacity + n, slot.version)
      .then(() => {
        toast.success(`容量 +${n}`, '增容成功')
        onDone()
      })
      .catch((err) => {
        setSubmitting(false)
        if (isApiError(err)) {
          setErrorMsg(err.message)
        } else {
          setErrorMsg('增容失败')
        }
      })
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !submitting) onClose() }}>
      <DialogHeader>
        <DialogTitle>增容场次</DialogTitle>
        <DialogDescription>
          {slot.slotDate} {String(slot.slotHour).padStart(2, '0')}:00 · 当前容量 {slot.capacity} · 版本 v{slot.version}
          <br />
          增容只增不减（已被抢走的名额无法回收），DB 与 Redis 桶余量将同步增加。
        </DialogDescription>
      </DialogHeader>
      <form onSubmit={(e) => { e.preventDefault(); submit() }}>
        <div className="py-4 space-y-2">
          <label htmlFor="capacity-delta" className="text-sm font-medium text-foreground">增容数量</label>
          <Input
            id="capacity-delta"
            type="number"
            min={1}
            value={delta}
            onChange={(e) => setDelta(e.target.value)}
            placeholder="输入正整数，如 5"
            autoFocus
          />
          {errorMsg && <p role="alert" className="text-sm text-destructive">{errorMsg}</p>}
        </div>
        <DialogFooter className="gap-2">
          <Button type="button" variant="outline" onClick={onClose} disabled={submitting}>取消</Button>
          <Button type="submit" disabled={submitting}>
            {submitting ? '提交中…' : '确认增容'}
          </Button>
        </DialogFooter>
      </form>
      {!submitting && <DialogClose onClick={onClose} />}
    </Dialog>
  )
}
