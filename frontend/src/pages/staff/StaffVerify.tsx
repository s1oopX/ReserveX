import { useState, useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'
import { QrCode, FileText, Scan, RefreshCw, AlertTriangle } from 'lucide-react'
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

export default function StaffVerify() {
  const [searchParams, setSearchParams] = useSearchParams()
  const initialTab = searchParams.get('tab') === 'manual' ? 'manual' : 'scan'
  const [tab, setTab] = useState<'scan' | 'manual'>(initialTab)

  const [scanPayload, setScanPayload] = useState<string>('')
  const [scanBusy, setScanBusy] = useState<boolean>(false)
  const [verifyResult, setVerifyResult] = useState<VerifyResultData | null>(null)
  const scanInputRef = useRef<HTMLInputElement>(null)

  const [manualRno, setManualRno] = useState<string>('')
  const [maskedConfirm, setMaskedConfirm] = useState<string>('')
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

  const handleContinueScan = () => {
    setScanPayload('')
    setVerifyResult(null)
    setTimeout(() => {
      scanInputRef.current?.focus()
    }, 50)
  }

  const handleManualSubmit = async () => {
    if (!manualRno.trim() || !maskedConfirm.trim() || manualBusy) return
    setManualBusy(true)
    setConfirmOpen(false)
    setVerifyResult(null)

    try {
      const res = await staffApi.verifyManual(manualRno.trim(), maskedConfirm.trim())
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
    <div className="space-y-6 max-w-2xl mx-auto">
      <div className="border-b pb-3">
        <h1 className="text-xl font-bold tracking-tight text-foreground font-serif">
          核销工作台
        </h1>
        <p className="text-xs text-muted-foreground mt-0.5">
          支持扫码枪快速核销与证件号人工手工补验
        </p>
      </div>

      <Tabs
        value={tab}
        onValueChange={(v) => {
          setTab(v as 'scan' | 'manual')
          setSearchParams({ tab: v })
          setVerifyResult(null)
        }}
      >
        <TabsList className="grid w-full grid-cols-2 h-12">
          <TabsTrigger value="scan" className="gap-2 text-sm font-semibold min-h-[44px]">
            <QrCode className="h-4 w-4" />
            <span>扫码枪核销</span>
          </TabsTrigger>
          <TabsTrigger value="manual" className="gap-2 text-sm font-semibold min-h-[44px]">
            <FileText className="h-4 w-4" />
            <span>手工登记核销</span>
          </TabsTrigger>
        </TabsList>

        <TabsContent value="scan" className="mt-4 space-y-4">
          <Card className="shadow-sm border">
            <CardHeader className="pb-3">
              <CardTitle className="text-base font-bold flex items-center gap-2">
                <Scan className="h-5 w-5 text-primary" />
                <span>扫码枪自动录入</span>
              </CardTitle>
              <CardDescription className="text-xs">
                页面加载后已自动聚焦输入框。扫码枪扫描完成后将自动提交验证。
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <form onSubmit={handleScanSubmit} className="space-y-3">
                <div className="space-y-1.5">
                  <Label htmlFor="scan-payload-input" className="text-xs font-semibold">
                    QR 码 Payload 载荷
                  </Label>
                  <Input
                    id="scan-payload-input"
                    ref={scanInputRef}
                    type="text"
                    required
                    placeholder="请使用扫码枪对准入园二维码扫描..."
                    value={scanPayload}
                    onChange={(e) => setScanPayload(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault()
                        handleScanSubmit()
                      }
                    }}
                    className="font-mono text-sm h-12 bg-card"
                  />
                </div>

                <div className="flex gap-2">
                  <Button
                    type="submit"
                    disabled={scanBusy || !scanPayload.trim()}
                    className="flex-1 min-h-[44px] font-semibold"
                  >
                    {scanBusy ? '验证中…' : '手动提交核销'}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => {
                      setScanPayload('')
                      scanInputRef.current?.focus()
                    }}
                    className="min-h-[44px]"
                  >
                    清空输入
                  </Button>
                </div>
              </form>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="manual" className="mt-4 space-y-4">
          <Card className="shadow-sm border">
            <CardHeader className="pb-3">
              <CardTitle className="text-base font-bold flex items-center gap-2">
                <FileText className="h-5 w-5 text-teal-700" />
                <span>手工凭证核销</span>
              </CardTitle>
              <CardDescription className="text-xs">
                手工核销会记录审计日志（MANUAL_VERIFY），请仔细核对游客证件。
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <form
                onSubmit={(e) => {
                  e.preventDefault()
                  if (manualRno.trim() && maskedConfirm.trim()) setConfirmOpen(true)
                }}
                className="space-y-3.5"
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
                    className="font-mono h-11"
                  />
                </div>

                <div className="space-y-1.5">
                  <Label htmlFor="manual-masked">脱敏身份证号确认值</Label>
                  <Input
                    id="manual-masked"
                    type="text"
                    required
                    placeholder="如: 110101********001X"
                    value={maskedConfirm}
                    onChange={(e) => setMaskedConfirm(e.target.value)}
                    className="font-mono h-11"
                  />
                  <p className="text-[11px] text-muted-foreground">
                    请输入完整脱敏串，需与后端预约记录保存的脱敏身份证号精确一致。
                  </p>
                </div>

                <Alert variant="warning" className="border-amber-300 bg-amber-50">
                  <AlertTriangle className="h-4 w-4 text-amber-700" />
                  <AlertDescription className="text-xs text-amber-900 font-medium">
                    提示：手工核销操作无法自动恢复，提交前必须核对游客出示的本人实体证件。
                  </AlertDescription>
                </Alert>

                <Button
                  type="submit"
                  disabled={manualBusy || !manualRno.trim() || !maskedConfirm.trim()}
                  className="w-full min-h-[44px] font-semibold"
                >
                  {manualBusy ? '处理中…' : '提交手工核销二次确认'}
                </Button>
              </form>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      {verifyResult && (
        <div className="space-y-3 pt-2">
          <VerifyResultView data={verifyResult} />

          <div className="flex justify-end">
            <Button onClick={handleContinueScan} className="gap-2 min-h-[44px]">
              <RefreshCw className="h-4 w-4" />
              <span>继续扫描 / 继续核销</span>
            </Button>
          </div>
        </div>
      )}

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
              <div>脱敏证件确认: {maskedConfirm}</div>
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
