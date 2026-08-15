import { useState, useEffect, useCallback } from 'react'
import { CalendarCheck, RefreshCw } from 'lucide-react'
import { staffApi } from '@/api/staff'
import { type ReservationVO } from '@/api/reservation'
import { isApiError } from '@/api/http'
import { todayInZone } from '@/lib/datetime'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'
import { EmptyState } from '@/components/common/EmptyState'

export default function StaffReservations() {
  const today = todayInZone()
  const [list, setList] = useState<ReservationVO[] | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')

  const load = useCallback(() => {
    setLoading(true)
    setErrorMsg('')
    setRequestId('')
    staffApi
      .today()
      .then((data) => {
        setList(data)
        setLoading(false)
      })
      .catch((err) => {
        setLoading(false)
        if (isApiError(err)) {
          setErrorMsg(err.message)
          setRequestId(err.requestId)
        } else {
          setErrorMsg('获取今日预约列表失败')
        }
      })
  }, [])

  useEffect(() => {
    load()
  }, [load])

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between border-b pb-3">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-foreground font-serif">
            今日预约列表
          </h1>
          <p className="text-xs text-muted-foreground mt-0.5">
            {today} · 查询与核对今日入园预约明细清单
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={load} className="gap-1.5 w-fit">
          <RefreshCw className="h-4 w-4" />
          <span>刷新</span>
        </Button>
      </div>

      {loading && (
        <Card className="p-6 space-y-3">
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
        </Card>
      )}

      {errorMsg && (
        <ErrorState title="加载今日预约失败" message={errorMsg} requestId={requestId} onRetry={load} />
      )}

      {!loading && !errorMsg && list && (
        <Card className="shadow-2xs border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>预约编号</TableHead>
                <TableHead>场次日期</TableHead>
                <TableHead>时段</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>证件号</TableHead>
                <TableHead>创建时间</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="text-center py-8 text-muted-foreground">
                    <CalendarCheck className="h-6 w-6 inline mr-2" />
                    今日暂无预约记录。
                  </TableCell>
                </TableRow>
              ) : (
                list.map((r) => (
                  <TableRow key={r.reservationNo}>
                    <TableCell className="font-mono text-xs font-bold text-foreground">{r.reservationNo}</TableCell>
                    <TableCell className="font-mono text-xs">{r.slotDate}</TableCell>
                    <TableCell className="font-mono text-xs">{String(r.slotHour).padStart(2, '0')}:00</TableCell>
                    <TableCell>
                      {r.status === 'CONFIRMED' ? (
                        <Badge variant="success">待入园</Badge>
                      ) : r.status === 'VERIFIED' ? (
                        <Badge variant="success">已核销</Badge>
                      ) : r.status === 'CANCELLED' ? (
                        <Badge variant="secondary">已取消</Badge>
                      ) : r.status === 'EXPIRED' ? (
                        <Badge variant="secondary">已过期</Badge>
                      ) : r.status === 'PENDING' ? (
                        <Badge variant="warning">确认中</Badge>
                      ) : (
                        <Badge variant="outline">{r.status}</Badge>
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{r.idCardMasked || '-'}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{r.createAt}</TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
          {list.length === 0 && (
            <div className="hidden">
              <EmptyState icon={<CalendarCheck className="h-8 w-8" />} title="" description="" />
            </div>
          )}
        </Card>
      )}
    </div>
  )
}
