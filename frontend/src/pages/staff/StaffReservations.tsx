import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { CalendarCheck, QrCode, RefreshCw } from 'lucide-react'
import { staffApi, type StaffReservationVO } from '@/api/staff'
import { isApiError } from '@/api/http'
import { todayInZone } from '@/lib/datetime'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'
import { EmptyState } from '@/components/common/EmptyState'
import { PageHeader } from '@/components/common/PageHeader'
import { ReservationStatusBadge } from '@/components/common/StatusBadge'

export default function StaffReservations() {
  const today = todayInZone()
  const [list, setList] = useState<StaffReservationVO[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [errorMsg, setErrorMsg] = useState('')
  const [requestId, setRequestId] = useState('')
  const [filter, setFilter] = useState<'all' | 'waiting' | 'verified' | 'other'>('all')

  const load = useCallback(() => {
    setLoading(true)
    setErrorMsg('')
    setRequestId('')
    staffApi.today().then((data) => {
      setList(data)
      setLoading(false)
    }).catch((err) => {
      setLoading(false)
      if (isApiError(err)) {
        setErrorMsg(err.message)
        setRequestId(err.requestId)
      } else setErrorMsg('获取今日预约列表失败')
    })
  }, [])

  useEffect(() => { load() }, [load])

  const filteredList = useMemo(() => (list || []).filter((reservation) => {
    if (filter === 'all') return true
    if (filter === 'waiting') return reservation.status === 'PENDING' || reservation.status === 'CONFIRMED'
    if (filter === 'verified') return reservation.status === 'VERIFIED'
    return !['PENDING', 'CONFIRMED', 'VERIFIED'].includes(reservation.status)
  }), [filter, list])

  const counts = useMemo(() => ({
    all: list?.length || 0,
    waiting: list?.filter((item) => item.status === 'PENDING' || item.status === 'CONFIRMED').length || 0,
    verified: list?.filter((item) => item.status === 'VERIFIED').length || 0,
    other: list?.filter((item) => !['PENDING', 'CONFIRMED', 'VERIFIED'].includes(item.status)).length || 0,
  }), [list])

  return (
    <div className="space-y-6">
      <PageHeader
        title="今日预约"
        description={`${today} · 当日预约与通行状态`}
        actions={<div className="flex gap-2"><Button variant="outline" onClick={load} className="h-10 gap-2"><RefreshCw className="h-4 w-4" /><span>刷新</span></Button><Button asChild className="h-10 gap-2"><Link to="/staff/verify?tab=scan"><QrCode className="h-4 w-4" />开始扫码</Link></Button></div>}
      />

      {loading && <Card className="space-y-3 p-5"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></Card>}
      {errorMsg && <ErrorState title="加载今日预约失败" message={errorMsg} requestId={requestId} onRetry={load} />}

      {!loading && !errorMsg && list && list.length === 0 && (
        <Card className="p-4"><EmptyState icon={<CalendarCheck className="h-8 w-8" />} title="今日暂无预约" description="有新预约后会显示在这里。" /></Card>
      )}

      {!loading && !errorMsg && list && list.length > 0 && (
        <div className="space-y-4">
          <div className="flex gap-2 overflow-x-auto pb-1" role="group" aria-label="预约状态筛选">
            <FilterButton active={filter === 'all'} onClick={() => setFilter('all')}>全部 <Count>{counts.all}</Count></FilterButton>
            <FilterButton active={filter === 'waiting'} onClick={() => setFilter('waiting')}>待入园 <Count>{counts.waiting}</Count></FilterButton>
            <FilterButton active={filter === 'verified'} onClick={() => setFilter('verified')}>已核销 <Count>{counts.verified}</Count></FilterButton>
            <FilterButton active={filter === 'other'} onClick={() => setFilter('other')}>其他状态 <Count>{counts.other}</Count></FilterButton>
          </div>

          {filteredList.length === 0 ? <Card className="p-4"><EmptyState icon={<CalendarCheck className="h-8 w-8" />} title="当前筛选没有预约" description="请选择其他状态查看今日预约。" /></Card> : <Card className="overflow-hidden">
          <div className="flex items-center justify-between border-b px-4 py-3">
            <span className="text-sm font-medium text-foreground">当前显示 {filteredList.length} 条</span>
            <span className="text-xs text-muted-foreground">列表不展示游客证件信息</span>
          </div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="whitespace-nowrap">预约编号</TableHead>
                <TableHead className="whitespace-nowrap">日期</TableHead>
                <TableHead className="whitespace-nowrap">入园时段</TableHead>
                <TableHead className="whitespace-nowrap">状态</TableHead>
                <TableHead className="whitespace-nowrap">创建时间</TableHead>
                <TableHead className="whitespace-nowrap text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredList.map((reservation) => (
                <TableRow key={reservation.reservationNo}>
                  <TableCell className="whitespace-nowrap font-mono text-xs font-semibold text-foreground">{reservation.reservationNo}</TableCell>
                  <TableCell className="whitespace-nowrap font-mono text-xs">{reservation.slotDate}</TableCell>
                  <TableCell className="whitespace-nowrap font-mono text-xs font-medium">{String(reservation.slotHour).padStart(2, '0')}:00</TableCell>
                  <TableCell className="whitespace-nowrap"><ReservationStatusBadge status={reservation.status} /></TableCell>
                  <TableCell className="whitespace-nowrap font-mono text-xs text-muted-foreground">{reservation.createAt}</TableCell>
                  <TableCell className="whitespace-nowrap text-right">{(reservation.status === 'PENDING' || reservation.status === 'CONFIRMED') ? <Button asChild variant="outline" size="sm"><Link to={`/staff/verify?tab=manual&rno=${encodeURIComponent(reservation.reservationNo)}`}>手工核销</Link></Button> : <span className="text-xs text-muted-foreground">无需操作</span>}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          </Card>}
        </div>
      )}
    </div>
  )
}

function FilterButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return <Button type="button" variant={active ? 'default' : 'outline'} onClick={onClick} className="h-10 shrink-0 gap-2">{children}</Button>
}

function Count({ children }: { children: number }) {
  return <span className="font-mono text-xs opacity-75">{children}</span>
}
