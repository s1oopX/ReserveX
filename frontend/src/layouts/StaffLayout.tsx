import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom'
import { ArrowLeftRight, CalendarCheck, LayoutDashboard, LogOut, QrCode } from 'lucide-react'
import { AppLogo } from '@/components/common/AppLogo'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

const navItems = [
  { path: '/staff/today', label: '今日工作台', shortLabel: '工作台', icon: LayoutDashboard },
  { path: '/staff/verify', label: '核销通行', shortLabel: '核销', icon: QrCode },
  { path: '/staff/reservations', label: '今日预约', shortLabel: '预约', icon: CalendarCheck },
]

export function StaffLayout() {
  const location = useLocation()
  const nav = useNavigate()
  const { role, logout } = useAuth()
  const isAdmin = role === 'ADMIN'

  const handleLogout = async () => {
    await logout()
    nav('/login', { replace: true })
  }

  return (
    <div className="app-shell min-h-screen">
      <header className="sticky top-0 z-40 border-b border-slate-200/80 bg-background/90 shadow-sm backdrop-blur">
        <div className="mx-auto flex h-[72px] max-w-7xl items-center gap-4 px-4 sm:px-6 lg:px-8">
          <div className="flex min-w-0 items-center gap-3">
            <AppLogo />
            <Badge variant="outline" className="hidden font-medium sm:inline-flex">现场核销</Badge>
          </div>

          <nav className="ml-auto hidden items-center gap-1 md:flex" aria-label="核销端主导航">
            {navItems.map((item) => {
              const Icon = item.icon
              const active = location.pathname === item.path
              return (
                <Link
                  key={item.path}
                  to={item.path}
                  aria-current={active ? 'page' : undefined}
                  className={cn(
                    'flex h-11 items-center gap-2 rounded-lg px-4 text-sm font-medium transition-colors',
                    active ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                  )}
                >
                  <Icon className="h-4 w-4" />
                  <span>{item.label}</span>
                </Link>
              )
            })}
          </nav>

          <div className="ml-auto flex items-center gap-1 md:ml-2">
            {isAdmin && (
              <Button variant="outline" onClick={() => nav('/admin/dashboard')} className="h-11 gap-2 px-3" aria-label="返回管理端">
                <ArrowLeftRight className="h-4 w-4" />
                <span className="hidden lg:inline">返回管理端</span>
              </Button>
            )}
            <Button variant="ghost" onClick={handleLogout} className="h-11 gap-2 px-3 text-muted-foreground hover:text-destructive" aria-label="退出登录">
              <LogOut className="h-4 w-4" />
              <span className="hidden sm:inline">退出</span>
            </Button>
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-7xl px-4 py-8 pb-28 sm:px-6 sm:py-10 md:pb-8 lg:px-8"><Outlet /></main>

      <nav className="fixed inset-x-0 bottom-0 z-40 grid h-20 grid-cols-3 border-t bg-background px-2 pb-[env(safe-area-inset-bottom)] md:hidden" aria-label="核销端移动导航">
        {navItems.map((item) => {
          const Icon = item.icon
          const active = location.pathname === item.path
          return (
            <Link key={item.path} to={item.path} aria-current={active ? 'page' : undefined} className={cn('flex min-w-0 flex-col items-center justify-center gap-1 text-xs font-medium transition-colors', active ? 'text-primary' : 'text-muted-foreground')}>
              <Icon className={cn('h-5 w-5', active && 'stroke-[2.5]')} />
              <span>{item.shortLabel}</span>
            </Link>
          )
        })}
      </nav>
    </div>
  )
}
