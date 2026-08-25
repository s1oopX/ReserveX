import { useState, useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'
import { QrCode, FileText, Scan, RefreshCw, AlertTriangle, ChevronRight, Database, KeyRound, ShieldCheck } from 'lucide-react'
import { staffApi } from '@/api/staff'
import { isApiError } from '@/api/http'
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { AlertDialog } from '@/components/ui/alert-dialog'
import { VerifyResultView, type VerifyResultData } from '@/components/common/VerifyResult'
import { PageHeader } from '@/components/common/PageHeader'

export default function StaffVerify() {
  const [searchParams, setSearchParams] = useSearchParams()
  const initialTab = searchParams.get('tab') === 'manual' ? 'manual' : 'scan'
  const [tab, setTab] = useState<'scan' | 'manual'>(initialTab)

  const [scanPayload, setScanPayload] = useState<string>('')
  const [scanBusy, setScanBusy] = useState<boolean>(false)
  const [verifyResult, setVerifyResult] = useState<VerifyResultData | null>(null)
  const scanInputRef = useRef<HTMLInputElement>(null)

  const [manualRno, setManualRno] = useState<string>(searchParams.get('rno') || '')
  const [idCardLast4, setIdCardLast4] = useState<string>('')
  const [manualBusy, setManualBusy] = useState<boolean>(false)
  const [confirmOpen, setConfirmOpen] = useState<boolean>(false)

  useEffect(() => {
    if (tab === 'scan') {
      scanInputRef.current?.focus()
    }
  }, [tab])

  const handleScanSubmit = async (e?: React.FormEvent) => {
    if (e) e.preventDefault()
    if (!scanPayload.trim() || scanBusy) return
    setScanBusy(true)
    setVerifyResult(null)

    try {
      const res = await staffApi.verifyScan(scanPayload.trim())
      setVerifyResult({
        type: 'success',
        reservationNo: res.reservationNo,
        verifyTime: res.verifyTime,
        staffId: res.staffId,
      })
    } catch (err) {
      if (isApiError(err)) {
        if (err.code === 'ALREADY_VERIFIED') {
          const firstView = err.data as { verifyTime?: string; staffId?: string } | null
          setVerifyResult({
            type: 'already_verified',
            reservationNo: (err.data as { reservationNo?: string })?.reservationNo || '未知',
            verifyTime: firstView?.verifyTime || null,
            staffId: firstView?.staffId || null,
          })
        } else {
          setVerifyResult({
            type: 'error',
            errorCode: err.code,
            errorMessage: err.message,
          })
        }
      } else {
        setVerifyResult({
          type: 'error',
          errorMessage: '核销请求异常，请检查网络后重试',
        })
      }
    } finally {
      setScanBusy(false)
    }
  }

  const handleNextVerification = () => {
    setVerifyResult(null)
    if (tab === 'scan') setScanPayload('')
    else {
      setManualRno('')
      setIdCardLast4('')
    }
    setTimeout(() => {
      if (tab === 'scan') scanInputRef.current?.focus()
    }, 50)
  }

  const handleCorrectAndRetry = () => {
    setVerifyResult(null)
    if (tab === 'scan') setTimeout(() => scanInputRef.current?.focus(), 50)
  }

  const handleManualSubmit = async () => {
    if (!manualRno.trim() || !idCardLast4.trim() || manualBusy) return
    setManualBusy(true)
    setConfirmOpen(false)
    setVerifyResult(null)

    try {
      const res = await staffApi.verifyManual(manualRno.trim(), idCardLast4.trim().toUpperCase())
      setVerifyResult({
        type: 'success',
        reservationNo: res.reservationNo,
        verifyTime: res.verifyTime,
        staffId: res.staffId,
      })
    } catch (err) {
      if (isApiError(err)) {
        if (err.code === 'ALREADY_VERIFIED') {
          const firstView = err.data as { verifyTime?: string; staffId?: string } | null
          setVerifyResult({
            type: 'already_verified',
            reservationNo: manualRno.trim(),
            verifyTime: firstView?.verifyTime || null,
            staffId: firstView?.staffId || null,
          })
        } else {
          setVerifyResult({
            type: 'error',
            errorCode: err.code,
            errorMessage: err.message,
          })
        }
      } else {
        setVerifyResult({
          type: 'error',
          errorMessage: '手工核销请求提交失败',
        })
      }
    } finally {
      setManualBusy(false)
    }
  }

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <PageHeader title="核销工作台" description="扫码核验优先，手工核验作为补充" />

      <Tabs
        value={tab}
        onValueChange={(v) => {
          setTab(v as 'scan' | 'manual')
          setSearchParams({ tab: v })
          setVerifyResult(null)
        }}
      >
        <TabsList className="grid h-14 w-full grid-cols-2">
          <TabsTrigger value="scan" className="min-h-[48px] gap-2 text-sm font-semibold">
            <QrCode className="h-4 w-4" />
            <span>扫码枪核销</span>
          </TabsTrigger>
          <TabsTrigger value="manual" className="min-h-[48px] gap-2 text-sm font-semibold">
            <FileText className="h-4 w-4" />
            <span>手工登记核销</span>
          </TabsTrigger>
        </TabsList>

        <TabsContent value="scan" className="mt-4 space-y-4">
          <Card className="overflow-hidden border-2 border-primary/25 shadow-lg shadow-primary/5">
            <CardHeader className="border-b bg-primary/[0.04] pb-4">
              <CardTitle className="flex items-center gap-2 text-lg font-semibold">
                <Scan className="h-5 w-5 text-primary" />
                <span>扫码枪自动录入</span>
              </CardTitle>
              <CardDescription className="text-sm">
                扫码枪扫描完成后按回车提交，结果会立即显示在下方。
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <form onSubmit={handleScanSubmit} className="space-y-4">
                <div className="space-y-1.5">
                  <Label htmlFor="scan-payload-input" className="text-sm font-semibold">
                    扫码枪输入
                  </Label>
                  <Input
                    id="scan-payload-input"
                    ref={scanInputRef}
                    type="text"
                    required
                    placeholder="请扫描游客动态入园码…"
                    value={scanPayload}
                    onChange={(e) => setScanPayload(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault()
                        handleScanSubmit()
                      }
                    }}
                    className="h-14 bg-background font-mono text-base"
                  />
                </div>

                <div className="grid gap-2 sm:grid-cols-[1fr_auto]">
                  <Button
                    type="submit"
                    disabled={scanBusy || !scanPayload.trim()}
                    className="h-14 text-base font-semibold"
                  >
                    {scanBusy ? '正在校验凭证…' : '提交核销'}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => {
                      setScanPayload('')
                      scanInputRef.current?.focus()
                    }}
                    className="h-14"
                  >
                    清空输入
                  </Button>
                </div>
              </form>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="manual" className="mt-4 space-y-4">
          <Card className="border shadow-sm">
            <CardHeader className="border-b bg-muted/20 pb-4">
              <CardTitle className="flex items-center gap-2 text-lg font-semibold">
                <FileText className="h-5 w-5 text-foreground" />
                <span>手工核销登记</span>
              </CardTitle>
              <CardDescription className="text-sm">
                手工核销会记录审计日志（MANUAL_VERIFY），请仔细核对游客证件。
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <form
                onSubmit={(e) => {
                  e.preventDefault()
                  if (manualRno.trim() && idCardLast4.trim()) setConfirmOpen(true)
                }}
                className="space-y-4"
              >
                <div className="space-y-1.5">
                  <Label htmlFor="manual-rno">预约编号 (rno)</Label>
                  <Input
                    id="manual-rno"
                    type="text"
                    required
                    placeholder="请输入 19 位预约编号字符串"
                    value={manualRno}
                    onChange={(e) => setManualRno(e.target.value)}
                    className="h-14 bg-background font-mono text-base"
                  />
                </div>

                <div className="space-y-1.5">
                  <Label htmlFor="manual-last4">身份证末四位</Label>
                  <Input
                    id="manual-last4"
                    type="text"
                    required
                    inputMode="text"
                    maxLength={4}
                    pattern="[0-9]{3}[0-9Xx]"
                    placeholder="请输入游客证件末四位"
                    value={idCardLast4}
                    onChange={(e) => setIdCardLast4(e.target.value.toUpperCase())}
                    className="h-14 bg-background font-mono text-base"
                  />
                  <p className="text-xs text-muted-foreground">
                    请让游客现场出示证件，输入末四位；最后一位为 X 时请使用 X。
                  </p>
                </div>

                <Alert variant="warning" className="border-amber-300 bg-amber-50">
                  <AlertTriangle className="h-4 w-4 text-amber-700" />
                  <AlertDescription className="text-sm font-medium text-amber-900">
                    提示：手工核销操作无法自动恢复，提交前必须核对游客出示的本人实体证件。
                  </AlertDescription>
                </Alert>

                <Button
                  type="submit"
                  disabled={manualBusy || !manualRno.trim() || !idCardLast4.trim()}
                  className="h-14 w-full text-base font-semibold"
                >
                  {manualBusy ? '处理中…' : '核对信息并继续'}
                </Button>
              </form>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      {verifyResult && (
        <div className="space-y-3 pt-2" role="status" aria-live="polite">
          <VerifyResultView data={verifyResult} />

          <div className="flex justify-end">
            <Button onClick={verifyResult.type === 'error' ? handleCorrectAndRetry : handleNextVerification} variant={verifyResult.type === 'error' ? 'outline' : 'default'} className="h-12 gap-2 font-semibold">
              <RefreshCw className="h-4 w-4" />
              <span>{verifyResult.type === 'error' ? '修改后重试' : tab === 'scan' ? '继续扫描' : '继续手工核销'}</span>
            </Button>
          </div>
        </div>
      )}

      <Card className="overflow-hidden">
        <details className="group">
          <summary className="flex cursor-pointer list-none items-center justify-between gap-4 p-5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset">
            <div className="flex items-center gap-3"><ShieldCheck className="h-5 w-5 text-primary" /><div><h2 className="font-semibold text-foreground">查看核销链路</h2><p className="mt-0.5 text-sm text-muted-foreground">仅展示系统实际执行的校验和状态迁移</p></div></div>
            <ChevronRight className="h-5 w-5 shrink-0 text-muted-foreground transition-transform group-open:rotate-90" />
          </summary>
          <div className="border-t bg-muted/20 p-5">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <ChainItem icon={<QrCode className="h-4 w-4" />} title="原始载荷" detail="前端不解析、不重排二维码内容" />
              <ChainItem icon={<KeyRound className="h-4 w-4" />} title="签名校验" detail="后端校验签名、版本与有效期" />
              <ChainItem icon={<Database className="h-4 w-4" />} title="CAS 更新" detail="预约状态仅能从待入园迁移一次" />
              <ChainItem icon={<FileText className="h-4 w-4" />} title="审计记录" detail="记录核销方式、员工与发生时间" />
            </div>
            {tab === 'manual' && <p className="mt-4 text-xs leading-5 text-amber-800">手工模式没有二维码签名可校验，因此强制要求证件末四位、二次确认并记录 MANUAL_VERIFY。</p>}
          </div>
        </details>
      </Card>

      <AlertDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="确认执行手工核销？"
        confirmText="确认核销并记录日志"
        cancelText="取消"
        busy={manualBusy}
        onConfirm={handleManualSubmit}
        description={
          <div className="space-y-2 text-sm text-foreground mt-2">
            <p>您即将对以下预约进行手工核销录入：</p>
            <div className="rounded bg-muted p-3 text-xs font-mono space-y-1">
              <div>预约编号: {manualRno}</div>
                  <div>证件末四位: {idCardLast4}</div>
            </div>
            <div className="text-xs text-muted-foreground">
              此操作将向审计日志写入 MANUAL_VERIFY 操作记录。
            </div>
          </div>
        }
      />
    </div>
  )
}

function ChainItem({ icon, title, detail }: { icon: React.ReactNode; title: string; detail: string }) {
  return <div className="rounded-lg border bg-background p-4"><div className="text-primary">{icon}</div><h3 className="mt-3 text-sm font-semibold text-foreground">{title}</h3><p className="mt-1 text-xs leading-5 text-muted-foreground">{detail}</p></div>
}
