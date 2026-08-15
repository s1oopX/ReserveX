import { AlertCircle, RefreshCw } from 'lucide-react'
import { Alert, AlertTitle, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { RequestIdHint } from './RequestIdHint'

interface ErrorStateProps {
  title?: string
  message: string
  requestId?: string
  onRetry?: () => void
}

export function ErrorState({
  title = '加载失败',
  message,
  requestId,
  onRetry,
}: ErrorStateProps) {
  return (
    <Alert variant="destructive" className="my-4">
      <AlertCircle className="h-5 w-5" />
      <AlertTitle className="font-semibold">{title}</AlertTitle>
      <AlertDescription className="mt-1">
        <p className="text-sm">{message}</p>
        {requestId && <RequestIdHint requestId={requestId} />}
        {onRetry && (
          <div className="mt-3">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={onRetry}
              className="gap-1.5 border-destructive/30 hover:bg-destructive/10 text-destructive"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              重新尝试
            </Button>
          </div>
        )}
      </AlertDescription>
    </Alert>
  )
}
