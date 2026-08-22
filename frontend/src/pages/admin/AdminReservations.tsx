import { useState, useEffect, useCallback } from 'react'
import { TicketCheck, Search, RefreshCw } from 'lucide-react'
import { adminApi, type AdminReservationVO } from '@/api/admin'
import { isApiError } from '@/api/http'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Select } from '@/components/ui/select'
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/common/EmptyState'
import { ErrorState } from '@/components/common/ErrorState'

type StatusFilter = '' | 'CONFIRMED' | 'VERIFIED' | 'CANCELLED' | 'EXPIRED'

export default function AdminReservations() {
  const [list, setList] = useState<AdminReservationVO[] | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')

  const [rno, setRno] = useState<string>('')
  const [slotDate, setSlotDate] = useState<string>('')
  const [status, setStatus] = useState<StatusFilter>('')

  const load = useCallback((cursor?: string) => {
    const append = Boolean(cursor)
    setLoading(!append)
    setErrorMsg('')
    setRequestId('')
    adminApi
      .listReservations({
        rno: rno.trim() || undefined,
        slotDate: slotDate || undefined,
        status: status || undefined,
        cursor,
        size: 100,
      })
      .then((data) => {
        setList((previous) => append && previous ? [...previous, ...data.items] : data.items)
        setHasMore(data.hasMore)
        setNextCursor(data.nextCursor)
        setLoading(false)
      })
      .catch((err) => {
        setLoading(false)
        if (isApiError(err)) {
          setErrorMsg(err.message)
          setRequestId(err.requestId)
        } else {
          setErrorMsg('获取全园预约列表失败')
        }
      })
  }, [rno, slotDate, status])

  useEffect(() => {
    load()
  }, [load])

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between border-b pb-3">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-foreground font-serif">
            全园预约明细管理
          </h1>
          <p className="text-xs text-muted-foreground mt-0.5">
            查询全园游客预约记录、追踪状态时间线与核销日志(跨分库广播)
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => load()} className="gap-1.5 w-fit">
          <RefreshCw className="h-4 w-4" />
          <span>刷新</span>
        </Button>
      </div>

      <Card className="shadow-2xs border">
        <div className="p-4 flex flex-wrap items-center gap-3">
          <div className="flex-1 min-w-[200px]">
            <Input
              placeholder="输入预约编号 (rno)..."
              value={rno}
              onChange={(e) => setRno(e.target.value)}
            />
          </div>
          <div className="w-44">
            <Input
              type="date"
              value={slotDate}
              onChange={(e) => setSlotDate(e.target.value)}
              placeholder="场次日期"
            />
          </div>
          <div className="w-40">
            <Select value={status} onChange={(e) => setStatus(e.target.value as StatusFilter)}>
              <option value="">全部状态</option>
              <option value="CONFIRMED">待入园</option>
              <option value="VERIFIED">已核销</option>
              <option value="CANCELLED">已取消</option>
              <option value="EXPIRED">已过期</option>
            </Select>
          </div>
          <Button className="gap-1.5" onClick={() => load()} disabled={loading}>
            <Search className="h-4 w-4" />
            <span>查询</span>
          </Button>
        </div>
      </Card>

      {loading && (
        <Card className="p-6 space-y-3">
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
        </Card>
      )}

      {errorMsg && (
        <ErrorState title="加载预约列表失败" message={errorMsg} requestId={requestId} onRetry={load} />
      )}

      {!loading && !errorMsg && list && (
        list.length === 0 ? (
          <EmptyState
            icon={<TicketCheck className="h-8 w-8" />}
            title="未查询到匹配的预约记录"
            description="调整筛选条件后重新查询。"
          />
        ) : (
          <div className="space-y-3">
          {hasMore && (
            <Button variant="outline" size="sm" onClick={() => nextCursor && load(nextCursor)} disabled={loading}>
              加载更多
            </Button>
          )}
          <Card className="shadow-2xs border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>预约编号</TableHead>
                  <TableHead>用户 ID</TableHead>
                  <TableHead>场次日期</TableHead>
                  <TableHead>场次 ID</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>证件号(脱敏)</TableHead>
                  <TableHead>创建时间</TableHead>
                  <TableHead>核销时间</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.map((r) => (
                  <TableRow key={r.reservationNo}>
                    <TableCell className="font-mono text-xs font-bold text-foreground">{r.reservationNo}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{r.userId}</TableCell>
                    <TableCell className="font-mono text-xs">{r.slotDate}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{r.slotId}</TableCell>
                    <TableCell>
                      {r.status === 'CONFIRMED' ? (
                        <Badge variant="success">待入园</Badge>
                      ) : r.status === 'VERIFIED' ? (
                        <Badge variant="secondary">已核销</Badge>
                      ) : r.status === 'CANCELLED' ? (
                        <Badge variant="outline">已取消</Badge>
                      ) : r.status === 'EXPIRED' ? (
                        <Badge variant="outline">已过期</Badge>
                      ) : (
                        <Badge variant="outline">{r.status}</Badge>
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{r.idCardMasked || '-'}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{r.createAt}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{r.verifyTime || '-'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
          </div>
        )
      )}
    </div>
  )
}
