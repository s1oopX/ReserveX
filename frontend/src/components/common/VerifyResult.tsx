import { CheckCircle2, AlertTriangle, XCircle, Clock, User, Hash } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'

export interface VerifyResultData {
  type: 'success' | 'already_verified' | 'error'
  reservationNo?: string
  verifyTime?: string | null
  staffId?: string | null
  errorCode?: string
  errorMessage?: string
}

export function VerifyResultView({ data }: { data: VerifyResultData }) {
  if (data.type === 'success') {
    return (
      <Card className="border-emerald-300 bg-emerald-50/70 shadow-md animate-in zoom-in-95">
        <CardContent className="p-6">
          <div className="flex items-center gap-3 text-emerald-800 font-bold text-lg mb-4">
            <CheckCircle2 className="h-7 w-7 text-emerald-600 shrink-0" />
            <span>核销成功</span>
          </div>
          <div className="space-y-2 text-sm text-emerald-950">
            {data.reservationNo && (
              <div className="flex items-center gap-2">
                <Hash className="h-4 w-4 text-emerald-700 shrink-0" />
                <span className="text-muted-foreground">预约编号:</span>
                <span className="font-mono font-semibold">{data.reservationNo}</span>
              </div>
            )}
            {data.verifyTime && (
              <div className="flex items-center gap-2">
                <Clock className="h-4 w-4 text-emerald-700 shrink-0" />
                <span className="text-muted-foreground">核销时间:</span>
                <span className="font-mono">{data.verifyTime}</span>
              </div>
            )}
            {data.staffId && (
              <div className="flex items-center gap-2">
                <User className="h-4 w-4 text-emerald-700 shrink-0" />
                <span className="text-muted-foreground">操作员工:</span>
                <span className="font-mono">{data.staffId}</span>
              </div>
            )}
          </div>
        </CardContent>
      </Card>
    )
  }

  if (data.type === 'already_verified') {
    return (
      <Card className="border-amber-300 bg-amber-50/70 shadow-md animate-in zoom-in-95">
        <CardContent className="p-6">
          <div className="flex items-center gap-3 text-amber-900 font-bold text-lg mb-4">
            <AlertTriangle className="h-7 w-7 text-amber-600 shrink-0" />
            <span>该预约已核销 (请勿重复通行)</span>
          </div>
          <div className="space-y-2 text-sm text-amber-950">
            {data.reservationNo && (
              <div className="flex items-center gap-2">
                <Hash className="h-4 w-4 text-amber-700 shrink-0" />
                <span className="text-muted-foreground">预约编号:</span>
                <span className="font-mono font-semibold">{data.reservationNo}</span>
              </div>
            )}
            {data.verifyTime && (
              <div className="flex items-center gap-2">
                <Clock className="h-4 w-4 text-amber-700 shrink-0" />
                <span className="text-muted-foreground">首次核销时间:</span>
                <span className="font-mono">{data.verifyTime}</span>
              </div>
            )}
            {data.staffId && (
              <div className="flex items-center gap-2">
                <User className="h-4 w-4 text-amber-700 shrink-0" />
                <span className="text-muted-foreground">首次操作员工:</span>
                <span className="font-mono">{data.staffId}</span>
              </div>
            )}
          </div>
        </CardContent>
      </Card>
    )
  }

  // Error case
  return (
    <Card className="border-destructive/40 bg-destructive/5 shadow-md animate-in zoom-in-95">
      <CardContent className="p-6">
        <div className="flex items-center gap-3 text-destructive font-bold text-lg mb-2">
          <XCircle className="h-7 w-7 text-destructive shrink-0" />
          <span>核销失败</span>
        </div>
        <p className="text-sm font-medium text-destructive/90 mt-1">
          {data.errorMessage || '无效凭证或预约状态不可核销'}
        </p>
        {data.errorCode && (
          <div className="mt-3 text-xs font-mono text-muted-foreground bg-background/80 p-2 rounded border border-destructive/20 inline-block">
            Error Code: {data.errorCode}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
