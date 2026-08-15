import { useState } from 'react'
import { Copy, Check } from 'lucide-react'
import { toast } from '@/components/ui/sonner'

export function RequestIdHint({ requestId }: { requestId: string }) {
  const [copied, setCopied] = useState(false)

  if (!requestId) return null

  const handleCopy = () => {
    navigator.clipboard?.writeText(requestId).then(
      () => {
        setCopied(true)
        toast.success('已复制 Request ID', requestId)
        setTimeout(() => setCopied(false), 2000)
      },
      () => {
        toast.error('复制失败，请手动复制')
      }
    )
  }

  return (
    <div className="mt-3 inline-flex items-center gap-1.5 rounded-md bg-muted/60 px-2.5 py-1 text-xs text-muted-foreground font-mono">
      <span>Request ID: {requestId}</span>
      <button
        type="button"
        onClick={handleCopy}
        className="inline-flex h-5 w-5 items-center justify-center rounded hover:bg-background transition-colors"
        title="复制 Request ID 用于排障"
        aria-label="复制 Request ID"
      >
        {copied ? <Check className="h-3 w-3 text-success" /> : <Copy className="h-3 w-3" />}
      </button>
    </div>
  )
}
