import { Outlet, Link, useNavigate, useLocation } from 'react-router-dom'
import { AppLogo } from '@/components/common/AppLogo'
import { ArrowLeft, Sparkles } from 'lucide-react'

export function PublicLayout() {
  const nav = useNavigate()
  const location = useLocation()
  const isWidePage = ['/notice', '/403', '/404', '/500'].includes(location.pathname)

  return (
    <div className="min-h-screen w-full relative font-sans overflow-x-hidden bg-slate-950 text-slate-100 flex flex-col justify-between selection:bg-emerald-500/20 selection:text-emerald-900">
      {/* Full-Bleed Nature Background with Living Breathing Ken-Burns Zoom */}
      <div
        className="fixed inset-0 bg-cover bg-center animate-ken-burns brightness-90 z-0"
        style={{ backgroundImage: "url('/wetland_hero.jpg')" }}
      />

      {/* Ambient Gradient Overlay */}
      <div className="fixed inset-0 bg-gradient-to-b from-slate-950/80 via-slate-950/65 to-slate-950/90 z-0" />
      <div className="fixed inset-0 bg-emerald-950/20 backdrop-blur-[2px] z-0" />

      {/* Lighting Glow Orbs */}
      <div className="fixed top-1/4 left-1/3 w-96 h-96 bg-emerald-500/15 rounded-full blur-3xl pointer-events-none z-0" />
      <div className="fixed bottom-10 right-1/3 w-96 h-96 bg-teal-400/10 rounded-full blur-3xl pointer-events-none z-0" />

      {/* Top Header Navbar */}
      <header className="relative z-10 sticky top-0 bg-slate-950/60 backdrop-blur-xl border-b border-white/10 shadow-lg">
        <div className="max-w-7xl mx-auto px-4 sm:px-8 h-16 sm:h-20 flex items-center justify-between">
          <Link to="/" className="focus:outline-none">
            <AppLogo subtitle />
          </Link>

          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => nav('/')}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-full text-xs font-semibold text-slate-200 hover:text-white bg-white/10 hover:bg-white/20 border border-white/15 backdrop-blur-md transition-all active:scale-95 cursor-pointer shadow-sm"
            >
              <ArrowLeft className="h-4 w-4" />
              <span>返回景区首页</span>
            </button>

            <div className="hidden sm:inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-emerald-500/20 border border-emerald-400/30 text-xs font-semibold text-emerald-200">
              <Sparkles className="h-3.5 w-3.5 text-amber-300 animate-pulse" />
              <span>国家 5A 级景区 · 官方预约</span>
            </div>
          </div>
        </div>
      </header>

      {/* Center Auth Form Container */}
      <main className="relative z-10 my-auto py-12 px-4 sm:px-8 w-full flex justify-center items-center flex-1">
        <div className={`w-full ${isWidePage ? 'max-w-3xl' : 'max-w-md'}`}>
          <Outlet />
        </div>
      </main>

      {/* Footer */}
      <footer className="relative z-10 border-t border-white/10 bg-slate-950/80 backdrop-blur-md py-6 text-xs text-slate-400 text-center">
        <div className="max-w-7xl mx-auto px-4 sm:px-8 space-y-1">
          <div>© 2026 ReserveX 湿地公园管理处 · 官方在线预约服务系统</div>
        </div>
      </footer>
    </div>
  )
}
