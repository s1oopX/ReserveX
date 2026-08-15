import { useState, useEffect, useCallback } from 'react'
import { Users, Plus, RefreshCw } from 'lucide-react'
import { adminApi, type StaffAccount } from '@/api/admin'
import { isApiError } from '@/api/http'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'
import { EmptyState } from '@/components/common/EmptyState'
import { CreateStaffDialog } from './CreateStaffDialog'

export default function AdminStaff() {
  const [list, setList] = useState<StaffAccount[] | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')
  const [creating, setCreating] = useState<boolean>(false)

  const load = useCallback(() => {
    setLoading(true)
    setErrorMsg('')
    setRequestId('')
    adminApi
      .listStaff()
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
          setErrorMsg('获取工作人员列表失败')
        }
      })
  }, [])

  useEffect(() => {
    load()
  }, [load])

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between border-b pb-4">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-foreground font-serif">
            工作人员账号管理
          </h1>
          <p className="text-xs text-muted-foreground mt-0.5">
            配置与管理入口核销人员 STAFF 账号（ADMIN 账号只能由 seed/bootstrap 产生）
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={load} className="gap-1.5">
            <RefreshCw className="h-4 w-4" />
            <span>刷新</span>
          </Button>
          <Button className="gap-2 font-semibold" onClick={() => setCreating(true)}>
            <Plus className="h-4 w-4" />
            <span>新建 STAFF 账号</span>
          </Button>
        </div>
      </div>

      {loading && (
        <Card className="p-6 space-y-3">
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
        </Card>
      )}

      {errorMsg && (
        <ErrorState title="加载工作人员列表失败" message={errorMsg} requestId={requestId} onRetry={load} />
      )}

      {!loading && !errorMsg && list && (
        <Card className="shadow-2xs border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>用户 ID</TableHead>
                <TableHead>邮箱</TableHead>
                <TableHead>手机号</TableHead>
                <TableHead>证件号</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>创建时间</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="text-center py-8 text-muted-foreground">
                    <Users className="h-6 w-6 inline mr-2" />
                    暂无工作人员账号。点击右上角新建。
                  </TableCell>
                </TableRow>
              ) : (
                list.map((s) => (
                  <TableRow key={s.userId}>
                    <TableCell className="font-mono text-xs text-muted-foreground">{s.userId}</TableCell>
                    <TableCell className="font-medium">{s.email}</TableCell>
                    <TableCell className="font-mono text-xs">{s.phone}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{s.idCardMasked}</TableCell>
                    <TableCell>
                      {s.status === 0 ? (
                        <Badge variant="success">正常</Badge>
                      ) : (
                        <Badge variant="destructive">封禁</Badge>
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{s.createAt}</TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
          {list.length === 0 && (
            <div className="hidden"><EmptyState icon={<Users className="h-8 w-8" />} title="" description="" /></div>
          )}
        </Card>
      )}

      {creating && (
        <CreateStaffDialog onClose={() => setCreating(false)} onDone={() => { setCreating(false); load() }} />
      )}
    </div>
  )
}
