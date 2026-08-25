import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  ArrowRight, Braces, Calendar, CalendarDays, Leaf, LogIn,
  MessageSquareMore, QrCode, RefreshCcw, UserPlus,
} from 'lucide-react'
import { reservationApi, type SlotVO } from '@/api/reservation'
import { AppLogo } from '@/components/common/AppLogo'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/context/AuthContext'
import { formatEpochSeconds, todayInZone } from '@/lib/datetime'

const engineeringProblems = [
  { icon: Braces, question: '放号瞬间不超卖', answer: '库存校验、用户判重、分桶选择与扣减在一次 Redis Lua 脚本内完成。', tag: '原子预占' },
  { icon: MessageSquareMore, question: '消息遗漏也能收口', answer: '预占同时留下待落库索引，Scanner 可重新投递，不把可靠性押在一次发送上。', tag: '可靠投递' },
  { icon: RefreshCcw, question: '重复消息不会重复写', answer: '消费幂等、唯一约束与状态条件更新共同处理至少一次投递。', tag: '幂等收敛' },
  { icon: QrCode, question: '凭证不能重复核销', answer: '动态签名防篡改，数据库 CAS 状态迁移阻止并发重复入园。', tag: 'CAS 核销' },
]

function slotState(slot: SlotVO) {
  if (!slot.released) return { label: '等待放号', className: 'border-amber-200 bg-amber-50 text-amber-800' }
  if (slot.full || slot.remain <= 0) return { label: '名额已满', className: 'border-rose-200 bg-rose-50 text-rose-800' }
  return { label: `余 ${slot.remain} 人`, className: 'border-emerald-200 bg-emerald-50 text-emerald-800' }
}

export default function LandingPage() {
  const nav = useNavigate()
  const { role } = useAuth()
  const [slots, setSlots] = useState<SlotVO[]>([])
  const [loadingSlots, setLoadingSlots] = useState(true)
  const [slotsFailed, setSlotsFailed] = useState(false)

  useEffect(() => {
    reservationApi.listSlots(todayInZone())
      .then((data) => setSlots(data.slice(0, 4)))
      .catch(() => setSlotsFailed(true))
      .finally(() => setLoadingSlots(false))
  }, [])

  const target = role === 'ADMIN' ? '/admin/dashboard' : role === 'STAFF' ? '/staff/today' : role === 'USER' ? '/slots' : '/login'
  const targetLabel = role === 'ADMIN' ? '进入运行概览' : role === 'STAFF' ? '进入核销工作台' : role === 'USER' ? '进入预约页' : '开始预约'

  return (
    <div className="min-h-screen bg-[#f5f8f7] text-slate-950">
      <header className="sticky top-0 z-40 border-b border-slate-200/80 bg-[#f8faf9]/95 backdrop-blur">
        <div className="mx-auto flex h-[72px] max-w-7xl items-center justify-between px-4 sm:px-6 lg:px-8">
          <Link to="/" aria-label="ReserveX 首页" className="rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><AppLogo subtitle /></Link>
          <nav className="hidden items-center gap-7 text-sm font-medium text-slate-600 md:flex" aria-label="首页导航">
            <a href="#today" className="transition-colors hover:text-primary">今日场次</a>
            <a href="#system" className="transition-colors hover:text-primary">系统设计</a>
            <Link to="/notice" className="transition-colors hover:text-primary">到访须知</Link>
            <Link to="/architecture" className="transition-colors hover:text-primary">技术架构</Link>
          </nav>
          <div className="flex items-center gap-2">
            {!role && <Button asChild variant="ghost" size="sm" className="hidden text-slate-600 sm:inline-flex"><Link to="/login"><LogIn className="h-4 w-4" />登录</Link></Button>}
            <Button onClick={() => nav(target)} size="sm" className="gap-1.5 px-4">{!role && <UserPlus className="h-4 w-4" />}{targetLabel}</Button>
          </div>
        </div>
      </header>

      <main>
        <section className="border-b border-slate-200 bg-[#f8faf9]">
          <div className="mx-auto grid max-w-7xl lg:grid-cols-[0.92fr_1.08fr]">
            <div className="flex flex-col justify-center px-4 py-10 sm:min-h-[610px] sm:px-6 sm:py-14 lg:px-8 lg:py-20">
              <Badge variant="outline" className="w-fit border-primary/25 bg-primary/[0.06] px-3 py-1 text-primary">分时开放 · 从容到访</Badge>
              <h1 className="mt-6 max-w-xl font-serif text-[40px] font-semibold leading-[1.12] text-[#123b43] sm:mt-7 sm:text-[62px]">把时间，<br />留给自然。</h1>
              <p className="mt-6 max-w-xl text-base leading-8 text-slate-600 sm:text-lg">选择合适的时段，实时查看余量。每一次预约都对应一张动态入园凭证，让现场核验更简单。</p>
              <div className="mt-6 space-y-3 text-sm text-slate-700 sm:mt-7 sm:space-y-3.5">
                <div className="flex items-center gap-3"><span className="flex h-9 w-9 items-center justify-center rounded-full bg-primary/10 text-primary"><Calendar className="h-4 w-4" /></span><span>实时查看场次余量，按自己的节奏预约</span></div>
                <div className="flex items-center gap-3"><span className="flex h-9 w-9 items-center justify-center rounded-full bg-primary/10 text-primary"><QrCode className="h-4 w-4" /></span><span>动态入园凭证，现场核验更清晰</span></div>
                <div className="flex items-center gap-3"><span className="flex h-9 w-9 items-center justify-center rounded-full bg-primary/10 text-primary"><Leaf className="h-4 w-4" /></span><span>分时控流，让自然与游客都更从容</span></div>
              </div>
              <div className="mt-7 flex flex-wrap gap-3 sm:mt-9"><Button onClick={() => nav(target)} size="lg" className="gap-2 px-7">{targetLabel}<ArrowRight className="h-4 w-4" /></Button><Button asChild variant="outline" size="lg" className="gap-2 bg-white"><Link to="/notice">了解入园安排</Link></Button></div>
            </div>

            <div id="today" className="relative min-h-[390px] overflow-hidden sm:min-h-[420px] lg:min-h-[610px]">
              <img src="/wetland_hero.jpg" alt="湿地公园步道与飞鸟" className="absolute inset-0 h-full w-full object-cover" />
              <div className="absolute inset-0 bg-white/20" aria-hidden="true" />
              <div className="relative flex h-full items-center justify-center px-4 py-8 sm:px-8 sm:py-12 lg:px-10">
                <section aria-labelledby="today-slots-title" className="w-full max-w-[590px] overflow-hidden rounded-2xl border border-white/70 bg-white/95 shadow-[0_20px_55px_rgba(16,47,56,0.18)] backdrop-blur-sm">
                  <div className="flex items-start justify-between gap-4 border-b border-slate-200 px-5 py-5 sm:px-6"><div><div className="flex items-center gap-2"><span className="flex h-9 w-9 items-center justify-center rounded-full bg-primary/10 text-primary"><CalendarDays className="h-4 w-4" /></span><h2 id="today-slots-title" className="font-serif text-xl font-semibold text-[#123b43]">今日可预约时段</h2></div><p className="mt-2 pl-11 text-xs text-slate-500">选择一个时间，继续查看预约信息</p></div><Badge variant="outline" className="shrink-0 text-slate-500">接口数据</Badge></div>
                  <div className="p-5 sm:p-6">
                    {loadingSlots && <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">{[1, 2, 3, 4].map((i) => <div key={i} className="h-32 animate-pulse rounded-xl border border-slate-200 bg-slate-50" />)}</div>}
                    {!loadingSlots && slotsFailed && <div className="flex min-h-32 flex-col items-center justify-center gap-3 text-center"><p className="text-sm font-medium text-slate-700">暂时无法读取今日场次</p><p className="text-xs text-slate-500">进入预约页后可再次加载实时数据。</p><Button onClick={() => nav(target)} size="sm" className="gap-1.5">进入预约页<ArrowRight className="h-3.5 w-3.5" /></Button></div>}
                    {!loadingSlots && !slotsFailed && slots.length === 0 && <div className="flex min-h-32 items-center justify-center text-sm text-slate-500">今日暂无已发布场次</div>}
                    {!loadingSlots && !slotsFailed && slots.length > 0 && <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">{slots.map((slot) => { const state = slotState(slot); const releaseAt = Number(slot.releaseAt) > 0 ? formatEpochSeconds(Number(slot.releaseAt)) : '已开放'; return <button key={slot.slotId} type="button" onClick={() => nav(target)} className="flex min-h-32 flex-col rounded-xl border border-slate-200 bg-white p-3 text-left transition hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><span className="font-mono text-[11px] text-slate-400">{slot.slotDate}</span><strong className="mt-3 font-mono text-xl text-[#123b43]">{String(slot.slotHour).padStart(2, '0')}:00</strong><span className={`mt-3 w-fit rounded-md border px-2 py-1 text-[11px] font-semibold ${state.className}`}>{state.label}</span><span className="mt-auto pt-3 text-[10px] leading-4 text-slate-400">放号：{releaseAt}</span></button> })}</div>}
                    <div className="mt-5 flex items-center justify-between border-t border-slate-200 pt-4 text-xs text-slate-500"><span>预约后可在个人中心查看动态凭证</span><Button onClick={() => nav(target)} variant="link" size="sm" className="h-auto gap-1 px-0">查看全部<ArrowRight className="h-3.5 w-3.5" /></Button></div>
                  </div>
                </section>
              </div>
            </div>
          </div>
        </section>

        <section id="system" className="bg-white py-12 sm:py-20"><div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8"><div className="grid gap-5 sm:gap-7 lg:grid-cols-[0.7fr_1.3fr] lg:items-end"><div><p className="font-mono text-xs font-semibold tracking-[0.12em] text-primary">SYSTEM DESIGN</p><h2 className="mt-3 font-serif text-3xl font-semibold leading-tight text-[#123b43] sm:mt-4 sm:text-4xl">一次预约，<br />看懂系统如何收口。</h2></div><p className="max-w-2xl text-sm leading-6 text-slate-600 sm:leading-7 lg:justify-self-end">场景是入口，机制是重点。下面用四个真实问题展示 ReserveX 如何处理抢号、投递、消费和核销。</p></div><div className="mt-7 grid grid-cols-2 gap-px overflow-hidden rounded-2xl border border-slate-200 bg-slate-200 sm:mt-10 md:grid-cols-2 xl:grid-cols-4">{engineeringProblems.map(({ icon: Icon, question, answer, tag }) => <article key={question} className="bg-[#fbfcfc] p-4 transition-colors hover:bg-white sm:p-7"><div className="flex items-center justify-between"><span className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary/10 text-primary sm:h-10 sm:w-10"><Icon className="h-4 w-4 sm:h-5 sm:w-5" /></span><span className="font-mono text-[9px] text-slate-400 sm:text-[10px]">{tag}</span></div><h3 className="mt-4 text-sm font-semibold leading-5 text-[#123b43] sm:mt-6 sm:min-h-12 sm:text-base sm:leading-6">{question}</h3><p className="mt-3 hidden text-sm leading-6 text-slate-500 sm:block">{answer}</p><Button asChild variant="link" className="mt-3 h-auto gap-1 px-0 text-xs text-primary sm:mt-4 sm:text-sm"><Link to="/architecture">查看链路<ArrowRight className="h-3.5 w-3.5 sm:h-4 sm:w-4" /></Link></Button></article>)}</div></div></section>

        <section className="bg-[#0c2830] py-12 text-white sm:py-20"><div className="mx-auto grid max-w-7xl gap-8 px-4 sm:gap-12 sm:px-6 lg:grid-cols-[0.68fr_1.32fr] lg:px-8"><div><Badge variant="outline" className="border-teal-200/20 bg-teal-200/10 text-teal-100">可靠性闭环</Badge><h2 className="mt-4 font-serif text-3xl font-semibold leading-tight sm:mt-5 sm:text-4xl">快路径负责抢到，<br />慢路径负责做对。</h2><p className="mt-4 text-sm leading-6 text-slate-300 sm:mt-5 sm:leading-7">Redis 预占、异步落库、补偿与对账共同完成跨系统一致性。真实运行指标只在接入观测后展示。</p><Button asChild className="mt-6 gap-2 bg-teal-500 text-white hover:bg-teal-400 sm:mt-7"><Link to="/architecture">阅读完整设计<ArrowRight className="h-4 w-4" /></Link></Button></div><div className="grid grid-cols-2 gap-2 sm:gap-3">{[['热路径', 'Redis Lua', '判重、选桶、扣减、预占一次完成'], ['异步路径', 'RocketMQ', '至少一次投递，由消费端承担幂等'], ['恢复路径', 'Scanner + Reconcile', '补投未持久化事件并校验差异'], ['核销路径', 'HMAC + CAS', '签名防篡改，条件更新防重复']].map(([eyebrow, title, detail]) => <div key={title} className="rounded-xl border border-white/10 bg-white/[0.045] p-4 sm:p-5"><div className="font-mono text-[9px] tracking-[0.1em] text-teal-300 sm:text-[10px] sm:tracking-[0.12em]">{eyebrow}</div><div className="mt-2 text-sm font-semibold sm:mt-3 sm:text-base">{title}</div><p className="mt-2 text-[11px] leading-4 text-slate-400 sm:text-xs sm:leading-5">{detail}</p></div>)}</div></div></section>

        <section className="border-b border-slate-200 bg-[#eef5f2] py-12 sm:py-14"><div className="mx-auto flex max-w-7xl flex-col gap-6 px-4 sm:px-6 md:flex-row md:items-center md:justify-between lg:px-8"><div><h2 className="font-serif text-2xl font-semibold text-[#123b43]">从一次真实预约开始。</h2><p className="mt-2 text-sm text-slate-600">先查看场次，再进入技术架构页了解完整处理链路。</p></div><div className="flex flex-wrap gap-3"><Button onClick={() => nav(target)} className="gap-2">{targetLabel}<ArrowRight className="h-4 w-4" /></Button><Button asChild variant="outline" className="bg-white"><Link to="/notice">查看预约规则</Link></Button></div></div></section>
      </main>

      <footer className="bg-white py-8"><div className="mx-auto flex max-w-7xl flex-col gap-4 px-4 text-sm sm:px-6 md:flex-row md:items-center md:justify-between lg:px-8"><div><div className="font-semibold text-[#123b43]">ReserveX</div><div className="mt-1 text-xs text-slate-500">高并发预约与一致性治理技术实践</div></div><div className="flex items-center gap-5 text-xs text-slate-500"><Link to="/architecture" className="hover:text-primary">技术架构</Link><Link to="/notice" className="hover:text-primary">预约规则</Link><Link to="/login" className="hover:text-primary">登录</Link></div><div className="text-xs text-slate-400">© 2026 ReserveX</div></div></footer>
    </div>
  )
}
