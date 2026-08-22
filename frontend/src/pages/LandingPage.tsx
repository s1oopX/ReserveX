import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { AppLogo } from '@/components/common/AppLogo'
import { useAuth } from '@/context/AuthContext'
import { reservationApi, SlotVO } from '@/api/reservation'
import { todayInZone, formatEpochSeconds } from '@/lib/datetime'
import {
  Sparkles,
  ArrowRight,
  ShieldCheck,
  Leaf,
  QrCode,
  Clock,
  ChevronRight,
  CheckCircle2,
  Calendar,
  LogIn,
  UserPlus,
  Landmark,
  MapPin
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'

export default function LandingPage() {
  const nav = useNavigate()
  const { role } = useAuth()
  const [slots, setSlots] = useState<SlotVO[]>([])
  const [loadingSlots, setLoadingSlots] = useState(true)

  useEffect(() => {
    async function loadPublicSlots() {
      try {
        const data = await reservationApi.listSlots(todayInZone())
        setSlots(data.slice(0, 4))
      } catch (err) {
        console.error('Failed to load landing slots', err)
      } finally {
        setLoadingSlots(false)
      }
    }
    loadPublicSlots()
  }, [])

  const handleAction = () => {
    if (role === 'USER') {
      nav('/slots')
    } else if (role === 'ADMIN') {
      nav('/admin/dashboard')
    } else if (role === 'STAFF') {
      nav('/staff/today')
    } else {
      nav('/login')
    }
  }

  return (
    <div className="min-h-screen w-full relative font-sans text-slate-100 bg-slate-950 overflow-x-hidden selection:bg-emerald-500/20 selection:text-emerald-900">
      {/* Ken Burns Background Nature Photo */}
      <div
        className="fixed inset-0 bg-cover bg-center animate-ken-burns brightness-90 z-0"
        style={{ backgroundImage: "url('/wetland_hero.jpg')" }}
      />
      <div className="fixed inset-0 bg-gradient-to-b from-slate-950/80 via-slate-950/65 to-slate-950/95 z-0" />
      <div className="fixed inset-0 bg-emerald-950/20 backdrop-blur-[2px] z-0" />

      {/* Lighting Glow Orbs */}
      <div className="fixed top-1/4 left-1/3 w-[500px] h-[500px] bg-emerald-500/15 rounded-full blur-3xl pointer-events-none z-0" />
      <div className="fixed bottom-10 right-1/4 w-[400px] h-[400px] bg-teal-400/10 rounded-full blur-3xl pointer-events-none z-0" />

      {/* Page Content Container */}
      <div className="relative z-10 flex flex-col min-h-screen">
        {/* Top Header Navbar */}
        <header className="sticky top-0 z-50 bg-slate-950/60 backdrop-blur-xl border-b border-white/10 shadow-lg">
          <div className="max-w-7xl mx-auto px-4 sm:px-8 h-16 sm:h-20 flex items-center justify-between">
            <AppLogo subtitle />

            {/* Desktop Navigation Links */}
            <nav className="hidden md:flex items-center gap-8 text-sm font-semibold tracking-tight text-slate-200">
              <a href="#slots" className="hover:text-emerald-300 transition-colors">放号预告</a>
              <a href="#features" className="hover:text-emerald-300 transition-colors">核心保障</a>
              <a href="#guide" className="hover:text-emerald-300 transition-colors">游览须知</a>
              <a href="#about" className="hover:text-emerald-300 transition-colors">公园概况</a>
            </nav>

            {/* Header Right Action CTA */}
            <div className="flex items-center gap-3">
              {role === 'USER' ? (
                <Button
                  onClick={() => nav('/slots')}
                  className="rounded-full bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs px-5 shadow-md shadow-emerald-600/20"
                >
                  进入预约控制台 <ArrowRight className="ml-1.5 h-3.5 w-3.5" />
                </Button>
              ) : role === 'ADMIN' ? (
                <Button
                  onClick={() => nav('/admin/dashboard')}
                  className="rounded-full bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs px-5 shadow-md shadow-emerald-600/20"
                >
                  进入管理后台 <ArrowRight className="ml-1.5 h-3.5 w-3.5" />
                </Button>
              ) : role === 'STAFF' ? (
                <Button
                  onClick={() => nav('/staff/today')}
                  className="rounded-full bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs px-5 shadow-md shadow-emerald-600/20"
                >
                  进入核销工作台 <ArrowRight className="ml-1.5 h-3.5 w-3.5" />
                </Button>
              ) : (
                <>
                  <Link to="/login">
                    <Button variant="ghost" className="text-xs text-slate-200 hover:text-white hover:bg-white/10 rounded-full gap-1.5 font-semibold">
                      <LogIn className="h-3.5 w-3.5" />
                      登录
                    </Button>
                  </Link>
                  <Link to="/register">
                    <Button className="rounded-full bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-600 text-white font-bold text-xs px-4 shadow-md shadow-emerald-600/25 gap-1.5">
                      <UserPlus className="h-3.5 w-3.5" />
                      注册账号
                    </Button>
                  </Link>
                </>
              )}
            </div>
          </div>
        </header>

        {/* Hero Banner Section */}
        <section className="max-w-7xl mx-auto px-4 sm:px-8 pt-12 sm:pt-20 pb-16 w-full text-center sm:text-left grid lg:grid-cols-12 gap-12 items-center">
          {/* Left Column Text & CTAs */}
          <div className="lg:col-span-7 space-y-6">
            <div className="inline-flex items-center gap-2 px-4 py-2 rounded-full bg-emerald-500/20 border border-emerald-400/30 text-xs font-semibold text-emerald-300 shadow-md backdrop-blur-md">
              <Sparkles className="h-4 w-4 text-amber-300 animate-pulse" />
              <span>国家 5A 级生态保护区 · 官方指定智慧分时准入平台</span>
            </div>

            <h1 className="text-4xl sm:text-5xl lg:text-[56px] font-black tracking-tight leading-[1.12] text-white drop-shadow-sm">
              栖居自然之美 <br />
              <span className="bg-gradient-to-r from-emerald-300 via-teal-200 to-emerald-100 bg-clip-text text-transparent">
                智慧实名分时预约
              </span>
            </h1>

            <p className="text-sm sm:text-base text-slate-200/95 font-normal leading-relaxed tracking-normal max-w-xl">
              恪守每日生态容量红线，提供数字化分时段放号、动态 60 秒加密验码、二代身份证直接通关与透明名额管理。
            </p>

            {/* Action Buttons */}
            <div className="pt-4 flex flex-wrap items-center justify-center sm:justify-start gap-4">
              <Button
                onClick={handleAction}
                size="lg"
                className="h-12 px-8 rounded-full bg-gradient-to-r from-emerald-600 via-emerald-700 to-teal-700 hover:from-emerald-500 hover:to-teal-600 text-white font-extrabold text-sm shadow-xl shadow-emerald-700/30 transition-all hover:scale-[1.02] gap-2"
              >
                <span>立即预约入园</span>
                <ArrowRight className="h-4 w-4" />
              </Button>

              <a href="#guide">
                <Button
                  variant="outline"
                  size="lg"
                  className="h-12 px-6 rounded-full border-white/20 bg-white/10 hover:bg-white/20 text-white text-sm backdrop-blur-md font-semibold"
                >
                  查看游览须知
                </Button>
              </a>
            </div>

            {/* Quick Metrics Bar */}
            <div className="pt-6 grid grid-cols-3 gap-4 border-t border-white/15 max-w-md">
              <div>
                <div className="text-2xl sm:text-3xl font-black font-mono tracking-tight text-white">5A 级</div>
                <div className="text-[11px] text-slate-300 font-medium">国家生态景区</div>
              </div>
              <div>
                <div className="text-2xl sm:text-3xl font-black font-mono tracking-tight text-emerald-300">100%</div>
                <div className="text-[11px] text-slate-300 font-medium">实名核验覆盖</div>
              </div>
              <div>
                <div className="text-2xl sm:text-3xl font-black font-mono tracking-tight text-teal-300">60 秒</div>
                <div className="text-[11px] text-slate-300 font-medium">动态防伪刷验</div>
              </div>
            </div>
          </div>

          {/* Right Column: Real-Time Live Capacity Preview Card */}
          <div id="slots" className="lg:col-span-5 w-full">
            <div className="bg-slate-900/80 backdrop-blur-2xl border border-white/20 rounded-3xl p-6 sm:p-8 shadow-2xl space-y-6">
              <div className="flex items-center justify-between border-b border-white/10 pb-4">
                <div className="space-y-1">
                  <h3 className="text-lg font-bold tracking-tight text-white flex items-center gap-2">
                    <Calendar className="h-5 w-5 text-emerald-400" />
                    <span>今日预约场次余量</span>
                  </h3>
                  <p className="text-xs text-slate-300">实时公开放号，分时段精确控制</p>
                </div>
                <Badge variant="outline" className="border-emerald-400/40 bg-emerald-500/20 text-emerald-300 text-xs px-2.5 py-1 font-mono">
                  实时数据
                </Badge>
              </div>

              {/* Slot Cards List */}
              <div className="space-y-3">
                {loadingSlots ? (
                  <div className="text-center py-8 text-xs text-slate-400 animate-pulse">
                    正在加载最新场次容量数据…
                  </div>
                ) : slots.length === 0 ? (
                  <div className="text-center py-8 text-xs text-slate-400">
                    今日暂无开放场次
                  </div>
                ) : (
                  slots.map((s) => {
                    const isAvailable = s.remain > 0
                    const releaseAtNum = Number(s.releaseAt) || 0
                    const formattedReleaseAt = releaseAtNum > 0 ? formatEpochSeconds(releaseAtNum) : '已放号'
                    return (
                      <div
                        key={s.slotId}
                        className="bg-white/10 border border-white/15 rounded-2xl p-4 flex items-center justify-between hover:bg-white/15 transition-all group"
                      >
                        <div className="space-y-1">
                          <div className="text-xs font-semibold text-slate-200 flex items-center gap-2">
                            <span className="font-mono">{s.slotDate}</span>
                            <span className="text-[11px] px-2 py-0.5 rounded-md bg-white/10 text-emerald-300 font-mono font-bold">
                              {String(s.slotHour).padStart(2, '0')}:00 场次
                            </span>
                          </div>
                          <div className="text-[11px] text-slate-400 font-mono">
                            放号时点: {formattedReleaseAt}
                          </div>
                        </div>

                        <div className="text-right space-y-1">
                          <div className="text-sm font-bold font-mono tracking-tight">
                            {isAvailable ? (
                              <span className="text-emerald-300">余 {s.remain} 张</span>
                            ) : (
                              <span className="text-rose-400">已约满</span>
                            )}
                          </div>
                          <Button
                            onClick={handleAction}
                            size="sm"
                            disabled={!isAvailable}
                            className="h-7 text-[11px] rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-bold px-3"
                          >
                            {isAvailable ? '立即抢约' : '不可约'}
                          </Button>
                        </div>
                      </div>
                    )
                  })
                )}
              </div>

              <div className="pt-2 text-center">
                <Button
                  onClick={handleAction}
                  variant="ghost"
                  className="text-xs text-emerald-300 hover:text-emerald-200 hover:bg-white/10 rounded-full gap-1 font-semibold"
                >
                  查看全部开放场次 <ChevronRight className="h-4 w-4" />
                </Button>
              </div>
            </div>
          </div>
        </section>

        {/* Feature Showcase Section */}
        <section id="features" className="max-w-7xl mx-auto px-4 sm:px-8 py-16 w-full border-t border-white/10">
          <div className="text-center max-w-2xl mx-auto space-y-3 mb-12">
            <h2 className="text-3xl font-extrabold tracking-tight text-white">四大生态与技术保障</h2>
            <p className="text-xs sm:text-sm text-slate-300">打造公平、透明、高效的国家级景区准入示范工程</p>
          </div>

          <div className="grid md:grid-cols-2 lg:grid-cols-4 gap-6">
            <div className="bg-white/10 border border-white/15 rounded-2xl p-6 space-y-3 backdrop-blur-md hover:bg-white/15 transition-all">
              <div className="p-3 rounded-xl bg-emerald-500/20 border border-emerald-400/30 text-emerald-300 w-fit">
                <Leaf className="h-6 w-6" />
              </div>
              <h3 className="text-base font-bold text-white tracking-tight">生态保护 · 分时放号</h3>
              <p className="text-xs text-slate-300 leading-relaxed font-normal">
                依据湿地生态承载力控制规范，将游览名额精确切分为多时段，杜绝瞬间人流过载。
              </p>
            </div>

            <div className="bg-white/10 border border-white/15 rounded-2xl p-6 space-y-3 backdrop-blur-md hover:bg-white/15 transition-all">
              <div className="p-3 rounded-xl bg-teal-500/20 border border-teal-400/30 text-teal-300 w-fit">
                <QrCode className="h-6 w-6" />
              </div>
              <h3 className="text-base font-bold text-white tracking-tight">60s 动态加密验码</h3>
              <p className="text-xs text-slate-300 leading-relaxed font-normal">
                二维码每 60 秒自动刷新加密载荷，有效拦截截图复制、二手倒卖与黄牛做假。
              </p>
            </div>

            <div className="bg-white/10 border border-white/15 rounded-2xl p-6 space-y-3 backdrop-blur-md hover:bg-white/15 transition-all">
              <div className="p-3 rounded-xl bg-emerald-500/20 border border-emerald-400/30 text-emerald-300 w-fit">
                <ShieldCheck className="h-6 w-6" />
              </div>
              <h3 className="text-base font-bold text-white tracking-tight">二代身份证直通核验</h3>
              <p className="text-xs text-slate-300 leading-relaxed font-normal">
                一证一天单次准入，全面对接智能闸机硬件，身份证刷卡即放行，3 秒快速入园。
              </p>
            </div>

            <div className="bg-white/10 border border-white/15 rounded-2xl p-6 space-y-3 backdrop-blur-md hover:bg-white/15 transition-all">
              <div className="p-3 rounded-xl bg-amber-500/20 border border-amber-400/30 text-amber-300 w-fit">
                <Landmark className="h-6 w-6" />
              </div>
              <h3 className="text-base font-bold text-white tracking-tight">过程全程透明审计</h3>
              <p className="text-xs text-slate-300 leading-relaxed font-normal">
                放号、抢约、改期、退约全链路审计留痕，名额实时对账，保证公平公正。
              </p>
            </div>
          </div>
        </section>

        {/* Visitor Guide & Announcement Section */}
        <section id="guide" className="max-w-7xl mx-auto px-4 sm:px-8 py-16 w-full border-t border-white/10">
          <div className="bg-slate-900/70 border border-white/15 rounded-3xl p-8 sm:p-10 backdrop-blur-xl grid lg:grid-cols-12 gap-8 items-center">
            <div className="lg:col-span-7 space-y-4">
              <div className="inline-flex items-center gap-1.5 text-xs font-semibold text-emerald-400 bg-emerald-500/20 px-3 py-1 rounded-full border border-emerald-400/30">
                <Clock className="h-3.5 w-3.5" />
                <span>官方游览须知</span>
              </div>
              <h2 className="text-2xl sm:text-3xl font-extrabold tracking-tight text-white">实名制准入与出示提醒</h2>
              <div className="space-y-3 text-xs sm:text-sm text-slate-200 font-normal">
                <div className="flex items-start gap-2.5">
                  <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0 mt-0.5" />
                  <span>开园时间 <strong className="font-mono font-bold">08:30 - 17:30</strong> (16:30 停止入园)，请按预约时段错峰准入。</span>
                </div>
                <div className="flex items-start gap-2.5">
                  <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0 mt-0.5" />
                  <span>入园时须持本人<strong>二代身份证原件</strong>与 60 秒动态二维码在闸机刷验。</span>
                </div>
                <div className="flex items-start gap-2.5">
                  <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0 mt-0.5" />
                  <span>禁止倒卖与虚假注册，异常高频抢约将被自动列入风控黑名单。</span>
                </div>
              </div>
            </div>

            <div id="about" className="lg:col-span-5 space-y-4 bg-white/5 border border-white/10 rounded-2xl p-6 backdrop-blur-md">
              <div className="flex items-center gap-2 text-xs font-bold text-emerald-300">
                <MapPin className="h-4 w-4" />
                <span>公园概况与地理位置</span>
              </div>
              <p className="text-xs text-slate-300 leading-relaxed font-normal">
                ReserveX 湿地公园包含核心湿地保护区、野生水鸟栖息区与自然科普栈道。为了保护珍稀动植物，全园区实施严格的保护区准入核验机制。
              </p>
              <div className="pt-2 text-xs text-slate-400 font-mono">
                客服热线：400-880-2026 (08:30-17:30)
              </div>
            </div>
          </div>
        </section>

        {/* Official Footer */}
        <footer className="mt-auto border-t border-white/10 bg-slate-950/80 backdrop-blur-md py-8 text-xs text-slate-400">
          <div className="max-w-7xl mx-auto px-4 sm:px-8 flex flex-col md:flex-row items-center justify-between gap-4 text-center md:text-left">
            <div className="space-y-1">
              <div className="font-bold text-white text-sm">ReserveX 国家级湿地公园</div>
              <div>主办单位： ReserveX 湿地公园管理处 · 官方在线预约服务系统</div>
            </div>
            <div className="text-[11px] font-mono text-slate-400">
              © 2026 ReserveX 湿地公园管理处 All Rights Reserved.
            </div>
          </div>
        </footer>
      </div>
    </div>
  )
}
