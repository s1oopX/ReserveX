import { Trees } from 'lucide-react'
import { cn } from '@/lib/utils'

interface AppLogoProps {
  className?: string
  iconOnly?: boolean
  subtitle?: boolean
  inverse?: boolean
}

export function AppLogo({ className, iconOnly = false, subtitle = false, inverse = false }: AppLogoProps) {
  return (
    <div className={cn('inline-flex items-center gap-2.5 select-none', className)}>
      <div className={cn('flex h-9 w-9 items-center justify-center rounded-xl border', inverse ? 'border-white/20 bg-white/10 text-white' : 'border-primary/15 bg-primary/10 text-primary')}>
        <Trees className="h-5 w-5" />
      </div>
      {!iconOnly && (
        <div className="flex flex-col">
          <span className={cn('text-lg font-bold leading-tight font-sans', inverse ? 'text-white' : 'text-foreground')}>
            ReserveX
          </span>
          {subtitle && (
            <span className={cn('text-[11px] font-medium', inverse ? 'text-slate-300' : 'text-muted-foreground')}>
              分时预约与入园核销
            </span>
          )}
        </div>
      )}
    </div>
  )
}
