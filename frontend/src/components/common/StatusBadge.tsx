import { Badge } from '@/components/ui/badge'

export type SlotStatusType = 'unreleased' | 'available' | 'full' | 'ended'

export function SlotStatusBadge({ status }: { status: SlotStatusType }) {
  switch (status) {
    case 'available':
      return <Badge variant="success">可预约</Badge>
    case 'unreleased':
      return <Badge variant="secondary">未放号</Badge>
    case 'full':
      return <Badge variant="destructive">已满</Badge>
    case 'ended':
      return <Badge variant="outline" className="text-muted-foreground">已结束</Badge>
  }
}

export type ReservationStatusType = 'CONFIRMED' | 'PENDING' | 'VERIFIED' | 'CANCELLED' | 'EXPIRED'

export function ReservationStatusBadge({ status }: { status: ReservationStatusType }) {
  switch (status) {
    case 'CONFIRMED':
      return <Badge variant="success">待入园</Badge>
    case 'PENDING':
      return <Badge variant="secondary" className="bg-emerald-100 text-emerald-800 border-emerald-200">预约已受理</Badge>
    case 'VERIFIED':
      return <Badge variant="secondary" className="bg-teal-100 text-teal-800 border-teal-200">已核销</Badge>
    case 'CANCELLED':
      return <Badge variant="outline" className="text-muted-foreground bg-muted/30">已取消</Badge>
    case 'EXPIRED':
      return <Badge variant="outline" className="text-amber-800 bg-amber-50 border-amber-200">已过期</Badge>
  }
}
