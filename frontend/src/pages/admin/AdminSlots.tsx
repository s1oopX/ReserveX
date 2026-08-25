import { useState, useEffect, useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Calendar, RefreshCw, Database, CheckCircle2, XCircle, PlusCircle } from 'lucide-react'
import { adminApi, type SlotDetail } from '@/api/admin'
import { isApiError } from '@/api/http'
import { todayInZone } from '@/lib/datetime'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'
import { IncreaseCapacityDialog } from './IncreaseCapacityDialog'
import { PageHeader } from '@/components/common/PageHeader'

export default function AdminSlots() {
  const today = todayInZone()
  const [searchParams, setSearchParams] = useSearchParams()
  const requestedDate = searchParams.get('date')
  const [date, setDate] = useState<string>(requestedDate && /^\d{4}-\d{2}-\d{2}$/.test(requestedDate) ? requestedDate : today)
  const [slots, setSlots] = useState<SlotDetail[] | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')
  const [increaseSlot, setIncreaseSlot] = useState<SlotDetail | null>(null)

  const loadSlots = useCallback(() => {
    setLoading(true)
    setErrorMsg('')
    setRequestId('')
    adminApi
      .listSlots(date)
      .then((data) => {
        setSlots(data)
        setLoading(false)
      })
      .catch((err) => {
        setLoading(false)
        if (isApiError(err)) {
          setErrorMsg(err.message)
          setRequestId(err.requestId)
        } else {
          setErrorMsg('获取场次日历数据失败')
        }
      })
  }, [date])

  useEffect(() => {
    loadSlots()
  }, [loadSlots])

  return (
    <div className="space-y-6">
      <PageHeader
        title="场次日历"
        description="按日期查看容量、余量、放号及 Redis 元数据状态"
        actions={(
          <div className="flex max-w-full flex-wrap justify-end gap-2">
          <div className="flex h-8 items-center gap-2 rounded-md border bg-background px-3">
            <Calendar className="h-4 w-4 text-primary" />
            <label htmlFor="admin-slot-date" className="sr-only">选择日期</label>
            <input
              id="admin-slot-date"
              type="date"
              value={date}
              onChange={(e) => { setDate(e.target.value); setSearchParams({ date: e.target.value }) }}
              className="bg-transparent text-sm font-medium text-foreground focus:outline-none cursor-pointer"
            />
          </div>
          <Button variant="outline" size="sm" onClick={loadSlots} disabled={loading} className="gap-1.5">
            <RefreshCw className="h-4 w-4" />
            <span>刷新</span>
          </Button>
          </div>
        )}
      />

      {loading && (
        <Card className="p-6 space-y-3">
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
        </Card>
      )}

      {errorMsg && (
        <ErrorState
          title="无法加载场次日历"
          message={errorMsg}
          requestId={requestId}
          onRetry={loadSlots}
        />
      )}

      {!loading && !errorMsg && slots && (
        <>
        <div className="grid gap-3 sm:grid-cols-3">
          <div className="rounded-lg border bg-card p-4"><p className="text-xs text-muted-foreground">已生成场次</p><p className="mt-1 font-mono text-2xl font-bold">{slots.length}</p></div>
          <div className="rounded-lg border bg-card p-4"><p className="text-xs text-muted-foreground">已放号</p><p className="mt-1 font-mono text-2xl font-bold text-emerald-700">{slots.filter((slot) => slot.released).length}</p></div>
          <div className="rounded-lg border bg-card p-4"><p className="text-xs text-muted-foreground">Redis 元数据缺失</p><p className={`mt-1 font-mono text-2xl font-bold ${slots.filter((slot) => slot.released && slot.metaPresent === false).length ? 'text-amber-700' : 'text-foreground'}`}>{slots.filter((slot) => slot.released && slot.metaPresent === false).length}</p></div>
        </div>
        <div className="overflow-hidden rounded-lg border bg-card">
          <Table className="min-w-[1080px]">
            <TableHeader>
              <TableRow>
                <TableHead>场次 ID</TableHead>
                <TableHead>日期与时段</TableHead>
                <TableHead>容量 / 剩余</TableHead>
                <TableHead>桶数量</TableHead>
                <TableHead>计划放号时间</TableHead>
                <TableHead>放号状态</TableHead>
                <TableHead>Redis 元数据</TableHead>
                <TableHead>版本号</TableHead>
                <TableHead className="text-right">增容操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {slots.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={9} className="text-center py-8 text-muted-foreground">
                    该日期（{date}）下尚未生成任何场次。
                  </TableCell>
                </TableRow>
              ) : (
                slots.map((slot) => (
                  <TableRow key={slot.slotId}>
                    <TableCell className="font-mono text-xs text-muted-foreground">{slot.slotId}</TableCell>
                    <TableCell className="font-bold font-mono">
                      {slot.slotDate} {String(slot.slotHour).padStart(2, '0')}:00
                    </TableCell>
                    <TableCell className="font-mono">
                      <span className="font-semibold text-foreground">{slot.capacity}</span>
                      {slot.remain != null && (
                        <span className="text-muted-foreground"> / 剩 {slot.remain}</span>
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-xs">{slot.bucketCount} 桶</TableCell>
                    <TableCell className="font-mono text-xs">{slot.releaseAt}</TableCell>
                    <TableCell>
                      {slot.released ? (
                        <Badge variant="success" className="gap-1">
                          <CheckCircle2 className="h-3 w-3" />
                          已放号
                        </Badge>
                      ) : (
                        <Badge variant="secondary">未放号</Badge>
                      )}
                    </TableCell>
                    <TableCell>
                      {slot.metaPresent === true ? (
                        <span className="inline-flex items-center gap-1 text-xs text-emerald-700 font-medium">
                          <Database className="h-3.5 w-3.5" /> 完整
                        </span>
                      ) : slot.metaPresent === false ? (
                        <span className="inline-flex items-center gap-1 text-xs text-amber-700 font-medium">
                          <XCircle className="h-3.5 w-3.5" /> 缺失
                        </span>
                      ) : (
                        <span className="text-xs text-muted-foreground">未返回</span>
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-xs">v{slot.version}</TableCell>
                    <TableCell className="text-right">
                      {slot.released ? (
                        <Button variant="outline" size="sm" className="text-xs gap-1.5"
                          onClick={() => setIncreaseSlot(slot)}>
                          <PlusCircle className="h-3.5 w-3.5" />
                          增容
                        </Button>
                      ) : (
                        <span className="text-xs text-muted-foreground">未放号不可增容</span>
                      )}
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
        </>
      )}

      {increaseSlot && (
        <IncreaseCapacityDialog
          slot={increaseSlot}
          onClose={() => setIncreaseSlot(null)}
          onDone={() => { setIncreaseSlot(null); loadSlots() }}
        />
      )}
    </div>
  )
}
