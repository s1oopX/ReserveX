import { Trees } from 'lucide-react'
import { cn } from '@/lib/utils'

interface AppLogoProps {
  className?: string
  iconOnly?: boolean
  subtitle?: boolean
}

export function AppLogo({ className, iconOnly = false, subtitle = false }: AppLogoProps) {
  return (
    <div className={cn('inline-flex items-center gap-2.5 select-none', className)}>
      <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-600 to-teal-700 text-white shadow-md shadow-emerald-700/25">
        <Trees className="h-5 w-5" />
      </div>
      {!iconOnly && (
        <div className="flex flex-col">
          <span className="text-lg font-extrabold tracking-tight text-white leading-tight font-sans">
            ReserveX
          </span>
          {subtitle && (
            <span className="text-[10.5px] tracking-wide text-slate-300 font-medium">
              湿地公园预约系统
            </span>
          )}
        </div>
      )}
    </div>
  )
}
