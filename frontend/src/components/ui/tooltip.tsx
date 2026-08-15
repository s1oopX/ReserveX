import * as React from 'react'
import { cn } from '@/lib/utils'

interface TooltipProps {
  content: React.ReactNode
  children: React.ReactElement
  className?: string
}

export function Tooltip({ content, children, className }: TooltipProps) {
  const [show, setShow] = React.useState(false)

  if (!content) return children

  return (
    <div
      className="relative inline-flex"
      onMouseEnter={() => setShow(true)}
      onMouseLeave={() => setShow(false)}
      onFocus={() => setShow(true)}
      onBlur={() => setShow(false)}
    >
      {children}
      {show && (
        <div
          role="tooltip"
          className={cn(
            'absolute bottom-full left-1/2 mb-2 -translate-x-1/2 z-50 whitespace-nowrap rounded-md bg-foreground px-3 py-1.5 text-xs text-background shadow-md animate-in fade-in-0 zoom-in-95 pointer-events-none',
            className
          )}
        >
          {content}
          <div className="absolute top-full left-1/2 -ml-1 border-4 border-transparent border-t-foreground" />
        </div>
      )}
    </div>
  )
}
