import * as React from 'react'
import { X } from 'lucide-react'
import { cn } from '@/lib/utils'

interface SheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  side?: 'bottom' | 'right' | 'left' | 'top'
  children: React.ReactNode
}

export function Sheet({ open, onOpenChange, side = 'bottom', children }: SheetProps) {
  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && open) {
        onOpenChange(false)
      }
    }
    if (open) {
      document.body.style.overflow = 'hidden'
      window.addEventListener('keydown', handleKeyDown)
    }
    return () => {
      document.body.style.overflow = ''
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [open, onOpenChange])

  if (!open) return null

  const sideStyles = {
    bottom: 'inset-x-0 bottom-0 max-h-[90vh] rounded-t-xl animate-in slide-in-from-bottom',
    top: 'inset-x-0 top-0 max-h-[90vh] rounded-b-xl animate-in slide-in-from-top',
    right: 'inset-y-0 right-0 h-full w-full max-w-sm rounded-l-xl animate-in slide-in-from-right',
    left: 'inset-y-0 left-0 h-full w-full max-w-sm rounded-r-xl animate-in slide-in-from-left',
  }

  return (
    <div className="fixed inset-0 z-50">
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-black/50 backdrop-blur-xs transition-opacity"
        onClick={() => onOpenChange(false)}
        aria-hidden="true"
      />
      {/* Sheet Content */}
      <div
        role="dialog"
        aria-modal="true"
        className={cn(
          'fixed z-50 bg-background p-6 shadow-xl border-t transition ease-in-out overflow-y-auto',
          sideStyles[side]
        )}
      >
        <button
          type="button"
          onClick={() => onOpenChange(false)}
          aria-label="关闭"
          className="absolute right-4 top-4 rounded-sm opacity-70 ring-offset-background transition-opacity hover:opacity-100 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
        >
          <X className="h-4 w-4" />
        </button>
        {children}
      </div>
    </div>
  )
}

export function SheetContent({ className, children, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('relative', className)} {...props}>{children}</div>
}

export function SheetHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col space-y-2 text-center sm:text-left pb-4', className)} {...props} />
}

export function SheetTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  return <h2 className={cn('text-lg font-semibold text-foreground', className)} {...props} />
}

export function SheetDescription({ className, ...props }: React.HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn('text-sm text-muted-foreground', className)} {...props} />
}

export function SheetFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col-reverse sm:flex-row sm:justify-end sm:space-x-2 pt-4 border-t mt-4', className)} {...props} />
}
