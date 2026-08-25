import { CheckCircle2, CircleDashed, Database, GitBranch, Radio } from 'lucide-react'

export type TechnicalTraceState = 'done' | 'active' | 'waiting'

export interface TechnicalTraceStep {
  label: string
  detail: string
  state: TechnicalTraceState
}

export function TechnicalTrace({ steps }: { steps: TechnicalTraceStep[] }) {
  return (
    <div className="rounded-xl border border-[#183d46] bg-[#102f38] p-4 text-white shadow-sm">
      <div className="mb-3 flex items-center justify-between gap-3">
        <div>
          <div className="text-sm font-semibold">本次请求的技术链路</div>
          <div className="mt-1 text-[11px] text-white/60">展示已发生的处理阶段，不虚构实时监控指标</div>
        </div>
        <GitBranch className="h-4 w-4 text-emerald-300" />
      </div>
      <div className="space-y-3">
        {steps.map((step, index) => {
          const Icon = index === 0 ? Radio : index === steps.length - 1 ? Database : step.state === 'active' ? CircleDashed : CheckCircle2
          return (
            <div key={step.label} className="flex items-start gap-3">
              <div className={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full ${step.state === 'done' ? 'bg-emerald-400/20 text-emerald-300' : step.state === 'active' ? 'bg-blue-400/20 text-blue-200' : 'bg-white/10 text-white/50'}`}>
                <Icon className={`h-3.5 w-3.5 ${step.state === 'active' ? 'animate-spin' : ''}`} />
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center justify-between gap-3 text-xs font-medium">
                  <span>{step.label}</span>
                  <span className={step.state === 'done' ? 'text-emerald-300' : step.state === 'active' ? 'text-blue-200' : 'text-white/50'}>{step.state === 'done' ? '已完成' : step.state === 'active' ? '处理中' : '等待'}</span>
                </div>
                <div className="mt-1 text-[11px] leading-5 text-white/60">{step.detail}</div>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
