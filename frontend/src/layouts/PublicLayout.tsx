import { Outlet, Link, useLocation } from 'react-router-dom'
import { ArrowLeft, CalendarCheck2, QrCode, ShieldCheck, Workflow } from 'lucide-react'
import { AppLogo } from '@/components/common/AppLogo'
import { Button } from '@/components/ui/button'

export function PublicLayout() {
  const location = useLocation()
  const isWidePage = ['/notice', '/403', '/404', '/500'].includes(location.pathname)
  const isAccountEntry = ['/login', '/register'].includes(location.pathname)

  return (
    <div className="flex min-h-screen flex-col bg-[#f5f8f7] text-foreground">
      <header className="sticky top-0 z-40 border-b border-slate-200/80 bg-white/90 backdrop-blur">
        <div className="mx-auto flex h-16 w-full max-w-6xl items-center justify-between px-4 sm:px-6">
          <Link to="/" className="rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <AppLogo subtitle />
          </Link>
          <div className="flex items-center gap-2">
            <div className="hidden text-xs font-medium text-muted-foreground sm:block">预约 · 凭证 · 核销</div>
            {location.pathname !== '/' && (
              <Button asChild variant="ghost" size="sm">
                <Link to="/">
                  <ArrowLeft className="h-4 w-4" />
                  返回首页
                </Link>
              </Button>
            )}
          </div>
        </div>
      </header>

      <main className="flex flex-1 items-center justify-center px-4 py-7 sm:px-6 sm:py-10">
        {isAccountEntry ? (
          <div className="grid w-full max-w-6xl gap-10 lg:grid-cols-[0.72fr_1.28fr] lg:items-center">
            <aside className="hidden max-w-sm lg:block">
              <div className="text-xs font-semibold tracking-[0.14em] text-primary">RESERVEX ACCOUNT</div>
              <h1 className="mt-4 font-serif text-4xl font-semibold leading-tight text-[#123b43]">一个账户，完成预约与凭证管理。</h1>
              <p className="mt-5 text-sm leading-7 text-slate-600">登录后查看真实场次余量、跟踪预约状态，并在到访时出示动态入园凭证。</p>
              <div className="mt-7 space-y-4 text-sm text-slate-700">
                <div className="flex items-center gap-3"><CalendarCheck2 className="h-4 w-4 text-primary" /><span>场次状态和剩余名额清晰可查</span></div>
                <div className="flex items-center gap-3"><QrCode className="h-4 w-4 text-primary" /><span>预约与动态凭证集中管理</span></div>
                <div className="flex items-center gap-3"><ShieldCheck className="h-4 w-4 text-primary" /><span>实名信息仅用于预约核验</span></div>
              </div>
              <Link to="/architecture" className="mt-8 inline-flex items-center gap-2 text-sm font-medium text-primary hover:underline">
                <Workflow className="h-4 w-4" />了解系统可靠性设计
              </Link>
            </aside>
            <div className={`w-full justify-self-end ${location.pathname === '/register' ? 'max-w-2xl' : 'max-w-md'}`}><Outlet /></div>
          </div>
        ) : (
          <div className={`w-full ${isWidePage ? 'max-w-3xl' : 'max-w-md'}`}><Outlet /></div>
        )}
      </main>

      <footer className="border-t border-slate-200 bg-white py-5 text-center text-xs text-muted-foreground">
        <div className="mx-auto max-w-6xl px-4">© 2026 ReserveX · 分时预约与入园核销系统</div>
      </footer>
    </div>
  )
}
