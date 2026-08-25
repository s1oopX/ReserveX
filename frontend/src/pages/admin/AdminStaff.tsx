import { useState, useEffect, useCallback } from 'react'
import { Ban, CircleCheckBig, Plus, RefreshCw, Users } from 'lucide-react'
import { adminApi, type StaffAccount } from '@/api/admin'
import { isApiError } from '@/api/http'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'
import { AlertDialog } from '@/components/ui/alert-dialog'
import { toast } from '@/components/ui/sonner'
import { CreateStaffDialog } from './CreateStaffDialog'
import { PageHeader } from '@/components/common/PageHeader'

export default function AdminStaff() {
  const [list, setList] = useState<StaffAccount[] | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')
  const [creating, setCreating] = useState<boolean>(false)
  const [selected, setSelected] = useState<StaffAccount | null>(null)
  const [changing, setChanging] = useState<boolean>(false)

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

  const changeStatus = async () => {
    if (!selected) return
    setChanging(true)
    const banned = selected.status === 0
    try {
      const updated = await adminApi.setStaffBanned(selected.userId, banned, selected.version)
      setList((rows) => rows?.map((row) => row.userId === updated.userId ? updated : row) ?? null)
      toast.success(banned ? '账号已封禁，现有会话已撤销' : '账号已解封，需重新登录')
      setSelected(null)
    } catch (err) {
      toast.error(isApiError(err) ? err.message : '账号状态更新失败')
    } finally {
      setChanging(false)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="工作人员账号"
        description="管理入口核销人员 STAFF 账号；ADMIN 账号由系统初始化"
        actions={(
          <div className="flex max-w-full flex-wrap justify-end gap-2">
            <Button variant="outline" size="sm" onClick={load} disabled={loading || changing} className="gap-1.5">
              <RefreshCw className="h-4 w-4" />
              <span>刷新</span>
            </Button>
            <Button size="sm" className="gap-2" onClick={() => setCreating(true)}>
              <Plus className="h-4 w-4" />
              <span>新建工作人员</span>
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
        <ErrorState title="加载工作人员列表失败" message={errorMsg} requestId={requestId} onRetry={load} />
      )}

      {!loading && !errorMsg && list && (
        <div className="overflow-hidden rounded-lg border bg-card">
          <Table className="min-w-[880px]">
            <TableHeader>
              <TableRow>
                <TableHead>用户 ID</TableHead>
                <TableHead>邮箱</TableHead>
                <TableHead>手机号</TableHead>
                <TableHead>证件号（脱敏）</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>创建时间</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} className="text-center py-8 text-muted-foreground">
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
                    <TableCell className="text-right">
                      <Button
                        size="sm"
                        variant={s.status === 0 ? 'destructive' : 'outline'}
                        className="gap-1.5"
                        onClick={() => setSelected(s)}
                      >
                        {s.status === 0
                          ? <Ban className="h-3.5 w-3.5" />
                          : <CircleCheckBig className="h-3.5 w-3.5" />}
                        {s.status === 0 ? '封禁' : '解封'}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      )}

      {creating && (
        <CreateStaffDialog onClose={() => setCreating(false)} onDone={() => { setCreating(false); load() }} />
      )}

      <AlertDialog
        open={selected !== null}
        onOpenChange={(open) => { if (!open && !changing) setSelected(null) }}
        title={selected?.status === 0 ? '封禁工作人员账号' : '解封工作人员账号'}
        description={selected?.status === 0
          ? `封禁 ${selected.email} 后，其 access 与 refresh 会话将立即失效。`
          : `解封 ${selected?.email ?? ''} 后，工作人员需要重新登录。`}
        confirmText={selected?.status === 0 ? '确认封禁' : '确认解封'}
        variant={selected?.status === 0 ? 'destructive' : 'default'}
        busy={changing}
        onConfirm={changeStatus}
      />
    </div>
  )
}
