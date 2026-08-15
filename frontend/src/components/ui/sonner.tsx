import * as React from 'react'
import { CheckCircle2, AlertCircle, Info, XCircle } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface ToastMessage {
  id: string
  title?: string
  description?: string
  type?: 'success' | 'error' | 'info' | 'warning'
}

type ToastListener = (toasts: ToastMessage[]) => void

let toasts: ToastMessage[] = []
const listeners = new Set<ToastListener>()

function notify() {
  listeners.forEach((l) => l([...toasts]))
}

export const toast = {
  success: (message: string, title?: string) => {
    const id = Math.random().toString(36).substring(2)
    toasts = [...toasts, { id, title: title || '成功', description: message, type: 'success' }]
    notify()
    setTimeout(() => toast.dismiss(id), 4000)
  },
  error: (message: string, title?: string) => {
    const id = Math.random().toString(36).substring(2)
    toasts = [...toasts, { id, title: title || '错误', description: message, type: 'error' }]
    notify()
    setTimeout(() => toast.dismiss(id), 5000)
  },
  warning: (message: string, title?: string) => {
    const id = Math.random().toString(36).substring(2)
    toasts = [...toasts, { id, title: title || '提示', description: message, type: 'warning' }]
    notify()
    setTimeout(() => toast.dismiss(id), 4000)
  },
  info: (message: string, title?: string) => {
    const id = Math.random().toString(36).substring(2)
    toasts = [...toasts, { id, title: title || '通知', description: message, type: 'info' }]
    notify()
    setTimeout(() => toast.dismiss(id), 4000)
  },
  dismiss: (id: string) => {
    toasts = toasts.filter((t) => t.id !== id)
    notify()
  },
}

export function Toaster() {
  const [items, setItems] = React.useState<ToastMessage[]>([])

  React.useEffect(() => {
    listeners.add(setItems)
    return () => {
      listeners.delete(setItems)
    }
  }, [])

  if (items.length === 0) return null

  return (
    <div
      aria-live="polite"
      className="fixed bottom-4 right-4 z-50 flex max-w-sm flex-col gap-2 pointer-events-none"
    >
      {items.map((t) => (
        <div
          key={t.id}
          className={cn(
            'pointer-events-auto flex items-start gap-3 rounded-lg border bg-background p-4 shadow-lg animate-in slide-in-from-bottom-5 duration-200',
            t.type === 'error' && 'border-destructive/40 bg-destructive/5 text-destructive',
            t.type === 'success' && 'border-success/40 bg-success/5 text-emerald-900',
            t.type === 'warning' && 'border-warning/40 bg-warning/5 text-amber-900',
            t.type === 'info' && 'border-primary/40 bg-primary/5 text-foreground'
          )}
        >
          {t.type === 'success' && <CheckCircle2 className="h-5 w-5 text-success shrink-0 mt-0.5" />}
          {t.type === 'error' && <XCircle className="h-5 w-5 text-destructive shrink-0 mt-0.5" />}
          {t.type === 'warning' && <AlertCircle className="h-5 w-5 text-warning shrink-0 mt-0.5" />}
          {t.type === 'info' && <Info className="h-5 w-5 text-primary shrink-0 mt-0.5" />}
          <div className="flex-1 text-sm">
            {t.title && <div className="font-semibold mb-0.5">{t.title}</div>}
            {t.description && <div className="text-xs text-muted-foreground">{t.description}</div>}
          </div>
          <button
            onClick={() => toast.dismiss(t.id)}
            className="text-muted-foreground hover:text-foreground text-xs"
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  )
}
