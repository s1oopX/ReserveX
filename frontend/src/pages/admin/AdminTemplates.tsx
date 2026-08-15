import { useState, useEffect } from 'react'
import { Plus, Edit3, Info } from 'lucide-react'
import { adminApi, type SlotTemplate } from '@/api/admin'
import { isApiError } from '@/api/http'
import { Code } from '@/api/codes'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/common/ErrorState'
import { toast } from '@/components/ui/sonner'
import { RequestIdHint } from '@/components/common/RequestIdHint'

export default function AdminTemplates() {
  const [templates, setTemplates] = useState<SlotTemplate[] | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string>('')
  const [requestId, setRequestId] = useState<string>('')

  const [open, setOpen] = useState<boolean>(false)
  const [editingItem, setEditingItem] = useState<SlotTemplate | null>(null)
  const [formBusy, setFormBusy] = useState<boolean>(false)
  const [formError, setFormError] = useState<string>('')
  const [formRequestId, setFormRequestId] = useState<string>('')

  const [slotHour, setSlotHour] = useState<number>(9)
  const [durationMin, setDurationMin] = useState<number>(120)
  const [capacity, setCapacity] = useState<number>(100)
  const [bucketCount, setBucketCount] = useState<number>(4)
  const [releaseOffsetMin, setReleaseOffsetMin] = useState<number>(-840)
  const [enabled, setEnabled] = useState<boolean>(true)

  const loadTemplates = () => {
    setLoading(true)
    setErrorMsg('')
    adminApi
      .listTemplates()
      .then((data) => {
        setTemplates(data)
        setLoading(false)
      })
      .catch((err) => {
        setLoading(false)
        if (isApiError(err)) {
          setErrorMsg(err.message)
          setRequestId(err.requestId)
        } else {
          setErrorMsg('获取场次模板列表失败')
        }
      })
  }

  useEffect(() => {
    loadTemplates()
  }, [])

  const handleOpenAdd = () => {
    setEditingItem(null)
    setSlotHour(9)
    setDurationMin(120)
    setCapacity(100)
    setBucketCount(4)
    setReleaseOffsetMin(-840)
    setEnabled(true)
    setFormError('')
    setFormRequestId('')
    setOpen(true)
  }

  const handleOpenEdit = (item: SlotTemplate) => {
    setEditingItem(item)
    setSlotHour(item.slotHour)
    setDurationMin(item.durationMin)
    setCapacity(item.capacity)
    setBucketCount(item.bucketCount)
    setReleaseOffsetMin(item.releaseOffsetMin)
    setEnabled(item.enabled)
    setFormError('')
    setFormRequestId('')
    setOpen(true)
  }

  const validate = (): string | null => {
    if (slotHour < 0 || slotHour > 23) return '时段必须在 0 到 23 小时之间'
    if (durationMin <= 0) return '时长必须大于 0 分钟'
    if (capacity <= 0) return '默认容量必须大于 0'
    if (bucketCount <= 0) return '分桶数必须大于 0'
    if (capacity < bucketCount) return '容量不能小于分桶数'
    if (releaseOffsetMin >= slotHour * 60) return '放号时间偏移必须早于场次开始时间'
    return null
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setFormError('')
    setFormRequestId('')

    const valErr = validate()
    if (valErr) {
      setFormError(valErr)
      return
    }

    setFormBusy(true)

    try {
      if (editingItem) {
        await adminApi.updateTemplate(editingItem.templateId, {
          slotHour,
          durationMin,
          capacity,
          bucketCount,
          releaseOffsetMin,
          enabled,
          version: editingItem.version,
        })
        toast.success('模板更新成功')
      } else {
        await adminApi.createTemplate({
          slotHour,
          durationMin,
          capacity,
          bucketCount,
          releaseOffsetMin,
          enabled,
        })
        toast.success('模板创建成功')
      }
      setOpen(false)
      loadTemplates()
    } catch (err) {
      if (isApiError(err)) {
        setFormRequestId(err.requestId)
        if (err.code === Code.TEMPLATE_INVALID) {
          setFormError('放号时点晚于场次结束，或容量小于分桶数')
        } else {
          setFormError(err.message)
        }
      } else {
        setFormError('提交失败，请检查参数或重试')
      }
    } finally {
      setFormBusy(false)
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between border-b pb-4">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-foreground font-serif">
            场次模板管理
          </h1>
          <p className="text-xs text-muted-foreground mt-0.5">
            配置定时批处理自动派生每日场次的基础模板规则
          </p>
        </div>
        <Button onClick={handleOpenAdd} className="gap-2 font-semibold">
          <Plus className="h-4 w-4" />
          <span>新建场次模板</span>
        </Button>
      </div>

      <Alert variant="warning" className="border-amber-300 bg-amber-50">
        <Info className="h-4 w-4 text-amber-700" />
        <AlertDescription className="text-xs text-amber-900 font-medium leading-relaxed">
          <strong>架构提示：</strong> 模板采用 copy-not-reference 复制逻辑。对模板的修改<strong>只影响之后自动生成的场次</strong>，不影响已生成的历史场次。
        </AlertDescription>
      </Alert>

      {loading && (
        <Card className="p-6 space-y-3">
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
        </Card>
      )}

      {errorMsg && (
        <ErrorState
          title="模板列表加载失败"
          message={errorMsg}
          requestId={requestId}
          onRetry={loadTemplates}
        />
      )}

      {!loading && !errorMsg && templates && (
        <Card className="shadow-2xs border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>场次时段</TableHead>
                <TableHead>时长</TableHead>
                <TableHead>默认容量</TableHead>
                <TableHead>分桶数量</TableHead>
                <TableHead>放号偏移(releaseOffsetMin)</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>版本号</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {templates.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={8} className="text-center py-8 text-muted-foreground">
                    暂无配置的场次模板，请点击上方按钮新建。
                  </TableCell>
                </TableRow>
              ) : (
                templates.map((tpl) => (
                  <TableRow key={tpl.templateId}>
                    <TableCell className="font-bold font-mono">
                      {String(tpl.slotHour).padStart(2, '0')}:00 时段
                    </TableCell>
                    <TableCell>{tpl.durationMin} 分钟</TableCell>
                    <TableCell className="font-semibold text-emerald-800">{tpl.capacity} 人</TableCell>
                    <TableCell className="font-mono">{tpl.bucketCount} 桶</TableCell>
                    <TableCell className="font-mono text-xs">
                      {tpl.releaseOffsetMin} 分钟
                    </TableCell>
                    <TableCell>
                      {tpl.enabled ? (
                        <Badge variant="success">已启用</Badge>
                      ) : (
                        <Badge variant="outline" className="text-muted-foreground">已停用</Badge>
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-xs">v{tpl.version}</TableCell>
                    <TableCell className="text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => handleOpenEdit(tpl)}
                        className="gap-1 text-xs"
                      >
                        <Edit3 className="h-3.5 w-3.5" />
                        <span>编辑</span>
                      </Button>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </Card>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{editingItem ? '编辑场次模板' : '新建场次模板'}</DialogTitle>
            <DialogDescription>
              {editingItem ? '编辑已有模板参数。修改后仅对新生产场次生效。' : '新建一个可自动派生放号的场次模板。'}
            </DialogDescription>
          </DialogHeader>

          <form onSubmit={handleSubmit} className="space-y-4 py-2">
            <div className="space-y-1.5">
              <Label htmlFor="tpl-hour">时段 (0–23 小时)</Label>
              <Input
                id="tpl-hour"
                type="number"
                min={0}
                max={23}
                required
                disabled={Boolean(editingItem)}
                value={slotHour}
                onChange={(e) => setSlotHour(Number(e.target.value))}
                className="font-mono"
              />
              {editingItem && (
                <p className="text-[11px] text-muted-foreground">
                  模板时段不可修改，若需更改时段请停用后新建。
                </p>
              )}
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="tpl-duration">游览时长 (分钟)</Label>
                <Input
                  id="tpl-duration"
                  type="number"
                  min={1}
                  required
                  value={durationMin}
                  onChange={(e) => setDurationMin(Number(e.target.value))}
                  className="font-mono"
                />
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="tpl-capacity">默认容量 (人)</Label>
                <Input
                  id="tpl-capacity"
                  type="number"
                  min={1}
                  required
                  value={capacity}
                  onChange={(e) => setCapacity(Number(e.target.value))}
                  className="font-mono"
                />
              </div>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="tpl-buckets">Redis / DB 分桶数量 (bucketCount)</Label>
              <Input
                id="tpl-buckets"
                type="number"
                min={1}
                required
                value={bucketCount}
                onChange={(e) => setBucketCount(Number(e.target.value))}
                className="font-mono"
              />
              <p className="text-[11px] text-muted-foreground">
                分桶数不能大于默认容量。
              </p>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="tpl-offset">放号时间偏移 (releaseOffsetMin)</Label>
              <Input
                id="tpl-offset"
                type="number"
                required
                value={releaseOffsetMin}
                onChange={(e) => setReleaseOffsetMin(Number(e.target.value))}
                className="font-mono"
              />
              <div className="rounded bg-muted p-2 text-xs text-muted-foreground font-mono leading-relaxed">
                💡 <strong>参数解释：</strong> 相对场次日期当天 00:00 的分钟偏移。
                <br />
                例如: <strong>-840</strong> 表示场次日期前一天 10:00 开放放号；<strong>480</strong> 表示场次当天 08:00 放号。
              </div>
            </div>

            <div className="flex items-center gap-2 pt-1">
              <input
                id="tpl-enabled"
                type="checkbox"
                checked={enabled}
                onChange={(e) => setEnabled(e.target.checked)}
                className="h-4 w-4 rounded border-input text-primary focus:ring-ring"
              />
              <Label htmlFor="tpl-enabled" className="text-sm font-medium cursor-pointer">
                启用该场次模板
              </Label>
            </div>

            {formError && (
              <Alert variant="destructive">
                <AlertDescription className="text-sm">
                  <div>{formError}</div>
                  {formRequestId && <RequestIdHint requestId={formRequestId} />}
                </AlertDescription>
              </Alert>
            )}

            <DialogFooter>
              <Button type="button" variant="outline" disabled={formBusy} onClick={() => setOpen(false)}>
                取消
              </Button>
              <Button type="submit" disabled={formBusy} className="font-semibold">
                {formBusy ? '正在保存…' : '保存模板'}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  )
}
