import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  ArrowLeft,
  ArrowRight,
  Braces,
  CheckCircle2,
  CircleAlert,
  Database,
  GitCompareArrows,
  LockKeyhole,
  MessageSquareMore,
  QrCode,
  RefreshCcw,
  ShieldCheck,
  Workflow,
  XCircle,
} from 'lucide-react'
import { AppLogo } from '@/components/common/AppLogo'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

type Track = 'grab' | 'consistency' | 'sharding' | 'verification'

const tracks: Record<Track, { label: string; title: string; summary: string; nodes: { name: string; role: string; icon: typeof Workflow; tone: string }[] }> = {
  grab: {
    label: '抢号链路',
    title: '热路径只做必须的事',
    summary: '请求进入后，库存正确性优先于同步落库。Redis Lua 在一次原子脚本内完成判重、分桶选择、扣减与预占记录。',
    nodes: [
      { name: 'HTTP + 分层限流', role: '拦截明显超频请求，减少无效竞争', icon: ShieldCheck, tone: 'border-emerald-300/30 bg-emerald-300/10 text-emerald-200' },
      { name: 'Redis Lua', role: '判重、选桶、扣减、写入 occupy', icon: Braces, tone: 'border-teal-300/30 bg-teal-300/10 text-teal-200' },
      { name: 'Pending 索引', role: '留下待持久化证据，不依赖 MQ 首次发送成功', icon: LockKeyhole, tone: 'border-amber-300/30 bg-amber-300/10 text-amber-200' },
    ],
  },
  consistency: {
    label: '最终一致',
    title: '快路径成功后，慢路径负责收口',
    summary: 'Redis 预占、消息投递和数据库写入不共享一个分布式事务，因此系统显式记录中间态，并用幂等消费、重试和对账完成收敛。',
    nodes: [
      { name: 'occupy + pending', role: '保留预占结果与待落库事件', icon: LockKeyhole, tone: 'border-teal-300/30 bg-teal-300/10 text-teal-200' },
      { name: 'RocketMQ', role: '异步传递持久化事件，允许至少一次投递', icon: MessageSquareMore, tone: 'border-sky-300/30 bg-sky-300/10 text-sky-200' },
      { name: 'Scanner + Reconcile', role: '补投遗漏并比较 Redis、DB 状态差异', icon: GitCompareArrows, tone: 'border-amber-300/30 bg-amber-300/10 text-amber-200' },
    ],
  },
  sharding: {
    label: '分片与持久化',
    title: '把路由约束写进数据访问',
    summary: '业务编号使用全局 ID，分片键查询通过路由信息完成定位；唯一性与幂等由数据库约束和消费状态共同承担。',
    nodes: [
      { name: '全局 ID', role: '生成可序列化的业务编号，避免单库自增冲突', icon: Workflow, tone: 'border-indigo-300/30 bg-indigo-300/10 text-indigo-200' },
      { name: 'ShardingSphere', role: '按分片键路由，避免跨库广播查询', icon: Database, tone: 'border-sky-300/30 bg-sky-300/10 text-sky-200' },
      { name: '唯一约束 + 幂等表', role: '让重复消费成为可识别、可跳过的操作', icon: CheckCircle2, tone: 'border-emerald-300/30 bg-emerald-300/10 text-emerald-200' },
    ],
  },
  verification: {
    label: '核销安全',
    title: '签名防篡改，CAS 防重复',
    summary: '动态 QR 解决凭证可信问题，但真正阻止并发重复入园的是数据库条件状态迁移和核销审计记录。',
    nodes: [
      { name: 'HMAC-SHA256', role: '校验 payload 完整性与 key-id', icon: QrCode, tone: 'border-sky-300/30 bg-sky-300/10 text-sky-200' },
      { name: '有效期 + nonce', role: '限制重放窗口并识别重复凭证', icon: RefreshCcw, tone: 'border-amber-300/30 bg-amber-300/10 text-amber-200' },
      { name: 'CAS 0 → 1', role: '只允许一个核销请求完成状态迁移', icon: CheckCircle2, tone: 'border-emerald-300/30 bg-emerald-300/10 text-emerald-200' },
    ],
  },
}

const failures = [
  ['MQ 发送失败', 'Pending Scanner 重新投递', '待落库索引仍存在，直到消息成功进入消费链路。', '补偿'],
  ['消息重复投递', '消费者幂等跳过', '消费事件、唯一键与状态条件更新共同保证不会重复写入。', '幂等'],
  ['消费者中途重启', '重试后继续持久化', '中间态可重放；已完成阶段由唯一约束保护。', '重试'],
  ['Redis 与 DB 有差异', '对账任务定位来源', '比较库存、预占和预约状态，差异进入人工处置队列。', '对账'],
  ['二维码重复使用', 'CAS 条件更新失败', '第一次核销完成后状态不再满足迁移条件。', '拒绝'],
]

export default function Architecture() {
  const [track, setTrack] = useState<Track>('grab')
  const selected = tracks[track]

  return (
    <div className="min-h-screen bg-[#0c2830] text-white">
      <header className="border-b border-white/10 bg-[#0c2830]/95 backdrop-blur">
        <div className="mx-auto flex h-[72px] max-w-[1440px] items-center justify-between px-4 sm:px-6 lg:px-10">
          <Link to="/" className="rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-300"><AppLogo inverse subtitle /></Link>
          <Button asChild variant="outline" size="sm" className="gap-2 border-white/15 bg-white/5 text-white hover:bg-white/10 hover:text-white"><Link to="/"><ArrowLeft className="h-4 w-4" />返回首页</Link></Button>
        </div>
      </header>

      <main>
        <section className="border-b border-white/10">
          <div className="mx-auto max-w-[1440px] px-4 py-16 sm:px-6 lg:px-10 lg:py-24">
            <div className="max-w-3xl"><Badge variant="outline" className="border-teal-200/20 bg-teal-200/10 text-teal-100">SYSTEM DESIGN / MECHANISM VIEW</Badge><h1 className="mt-6 text-4xl font-semibold leading-tight tracking-[-0.03em] sm:text-6xl">一次预约，<br />如何从抢号走到收敛。</h1><p className="mt-6 max-w-2xl text-base leading-8 text-slate-300 sm:text-lg">这里展示 ReserveX 的处理机制、失败路径与技术取舍。指标只有接入真实观测后才展示，页面不填充虚构 QPS。</p></div>
            <div className="mt-14 grid gap-px overflow-hidden rounded-2xl border border-white/10 bg-white/10 md:grid-cols-4">
              {(Object.keys(tracks) as Track[]).map((key) => <button key={key} type="button" onClick={() => setTrack(key)} className={`border-b border-white/10 p-5 text-left transition last:border-0 md:border-b-0 md:border-r md:last:border-r-0 ${track === key ? 'bg-teal-300/10' : 'bg-white/[0.025] hover:bg-white/[0.06]'}`}><div className={`text-xs font-medium ${track === key ? 'text-teal-200' : 'text-slate-400'}`}>{tracks[key].label}</div><div className="mt-3 text-sm font-semibold">{tracks[key].title}</div></button>)}
            </div>
          </div>
        </section>

        <section className="border-b border-white/10 bg-[#102f38]">
          <div className="mx-auto max-w-[1440px] px-4 py-16 sm:px-6 lg:px-10">
            <div className="grid gap-10 lg:grid-cols-[0.65fr_1.35fr]">
              <div><p className="font-mono text-xs tracking-[0.16em] text-teal-300">{selected.label.toUpperCase()}</p><h2 className="mt-4 text-3xl font-semibold leading-tight tracking-[-0.025em]">{selected.title}</h2><p className="mt-5 text-sm leading-7 text-slate-300">{selected.summary}</p></div>
              <div className="space-y-3">{selected.nodes.map(({ name, role, icon: Icon, tone }, index) => <div key={name} className="flex items-center gap-4"><div className="flex flex-1 items-center gap-4 rounded-xl border border-white/10 bg-white/[0.045] p-5"><span className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-lg border ${tone}`}><Icon className="h-5 w-5" /></span><div><div className="font-mono text-sm font-semibold">{name}</div><p className="mt-1 text-xs leading-5 text-slate-400">{role}</p></div></div>{index < selected.nodes.length - 1 && <ArrowRight className="hidden h-5 w-5 shrink-0 text-teal-300/50 sm:block" />}</div>)}</div>
            </div>
          </div>
        </section>

        <section className="border-b border-white/10 bg-[#f5f8f7] text-slate-900">
          <div className="mx-auto max-w-[1440px] px-4 py-16 sm:px-6 lg:px-10">
            <div className="grid gap-8 lg:grid-cols-[0.68fr_1.32fr] lg:items-end"><div><p className="font-mono text-xs font-semibold tracking-[0.16em] text-primary">FAILURE MATRIX</p><h2 className="mt-4 text-3xl font-semibold leading-tight tracking-[-0.025em] text-[#102f38]">系统如何面对失败。</h2></div><p className="max-w-2xl text-sm leading-7 text-slate-600 lg:justify-self-end">失败不是按钮旁的一句“请重试”，而是有明确来源、恢复动作和正确性边界的系统状态。</p></div>
            <div className="mt-10 overflow-x-auto rounded-2xl border border-slate-200 bg-white"><table className="w-full min-w-[720px] border-collapse text-left text-sm"><thead className="bg-slate-50 text-xs text-slate-500"><tr><th className="px-5 py-4 font-medium">故障</th><th className="px-5 py-4 font-medium">系统动作</th><th className="px-5 py-4 font-medium">正确性保障</th><th className="px-5 py-4 font-medium">路径</th></tr></thead><tbody>{failures.map(([failure, action, guard, path]) => <tr key={failure} className="border-t border-slate-100"><td className="px-5 py-4 font-medium text-[#102f38]"><span className="flex items-center gap-2"><CircleAlert className="h-4 w-4 text-amber-600" />{failure}</span></td><td className="px-5 py-4 text-slate-700">{action}</td><td className="max-w-sm px-5 py-4 text-slate-500">{guard}</td><td className="px-5 py-4"><Badge variant="outline" className="font-mono text-[10px]">{path}</Badge></td></tr>)}</tbody></table></div>
          </div>
        </section>

        <section className="bg-[#f5f8f7] text-slate-900"><div className="mx-auto grid max-w-[1440px] gap-8 px-4 py-16 sm:px-6 lg:grid-cols-2 lg:px-10"><article className="rounded-2xl border border-slate-200 bg-white p-7"><div className="flex items-center gap-2 text-sm font-semibold text-[#102f38]"><CheckCircle2 className="h-4 w-4 text-primary" />明确的正确性边界</div><h2 className="mt-4 text-2xl font-semibold text-[#102f38]">宁可少卖，不超卖。</h2><p className="mt-3 text-sm leading-7 text-slate-600">Redis 不可写或关键元数据缺失时，系统拒绝继续抢号，而不是降级到不受保护的数据库扣减。可用性让位于库存正确性。</p></article><article className="rounded-2xl border border-slate-200 bg-white p-7"><div className="flex items-center gap-2 text-sm font-semibold text-[#102f38]"><XCircle className="h-4 w-4 text-rose-600" />不伪造观测结果</div><h2 className="mt-4 text-2xl font-semibold text-[#102f38]">机制和指标分开。</h2><p className="mt-3 text-sm leading-7 text-slate-600">本页解释实现路径；QPS、p99、积压、差异数量等运行证据应来自真实监控或压测报告，接入后再展示。</p></article></div></section>
      </main>

      <footer className="border-t border-white/10 bg-[#0c2830] py-8"><div className="mx-auto flex max-w-[1440px] flex-col gap-3 px-4 text-xs text-slate-400 sm:px-6 md:flex-row md:items-center md:justify-between lg:px-10"><span>ReserveX · 高并发预约与一致性治理技术实践</span><Link to="/" className="text-teal-200 hover:text-white">返回预约入口 <ArrowRight className="inline h-3.5 w-3.5" /></Link></div></footer>
    </div>
  )
}
