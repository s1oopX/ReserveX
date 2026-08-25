import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom'
import {
  LayoutDashboard,
  Clock,
  CalendarDays,
  Activity,
  TicketCheck,
  Scale,
  Users,
  QrCode,
  LogOut,
  ChevronRight,
} from 'lucide-react'
import { AppLogo } from '@/components/common/AppLogo'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

export function AdminLayout() {
  const location = useLocation()
  const nav = useNavigate()
  const { logout } = useAuth()

  const handleLogout = async () => {
    await logout()
    nav('/login', { replace: true })
  }

  const menuItems = [
    { path: '/admin/dashboard', label: '运行概览', icon: LayoutDashboard },
    { path: '/admin/templates', label: '场次模板', icon: Clock },
    { path: '/admin/slots', label: '场次日历', icon: CalendarDays },
    { path: '/admin/release-monitor', label: '发布监控', icon: Activity },
    { path: '/admin/reservations', label: '预约管理', icon: TicketCheck },
    { path: '/admin/reconcile', label: '异常处置', icon: Scale },
    { path: '/admin/staff', label: '工作人员', icon: Users },
    { path: '/staff/verify', label: '核销工作台', icon: QrCode },
  ]

  const currentItem = menuItems.find((m) => m.path === location.pathname) || {
    label: '运营管理',
  }

  return (
    <div className="app-shell flex min-h-screen">
      <aside className="hidden w-64 border-r border-slate-200/80 bg-card lg:flex flex-col shrink-0">
        <div className="px-5 py-5 border-b">
          <AppLogo subtitle />
          <p className="mt-4 text-xs font-medium text-muted-foreground">
            预约运行与异常处置
          </p>
        </div>
        <nav className="flex-1 p-3 space-y-0.5 overflow-y-auto" aria-label="管理端主导航">
          {menuItems.map((item) => {
            const Icon = item.icon
            const active = location.pathname === item.path
            return (
              <Link
                key={item.path}
                to={item.path}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'flex items-center gap-3 rounded-lg px-3.5 py-3 text-sm font-medium transition-colors group',
                  active
                    ? 'bg-primary text-primary-foreground font-semibold shadow-xs'
                    : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'
                )}
              >
                <Icon className={cn('h-4 w-4 shrink-0', active ? 'text-primary-foreground' : 'text-muted-foreground group-hover:text-foreground')} />
                <span className="flex-1">{item.label}</span>
                {active && <ChevronRight className="h-4 w-4 shrink-0 text-primary-foreground/70" />}
              </Link>
            )
          })}
        </nav>
        <div className="px-4 py-3 border-t text-xs text-muted-foreground">
          ReserveX · 管理端
        </div>
      </aside>

      <div className="flex-1 flex flex-col min-w-0">
        <header className="h-[72px] border-b border-slate-200/80 bg-card px-4 sm:px-6 lg:px-8 flex items-center justify-between shrink-0">
          <div className="flex min-w-0 items-center gap-2 text-sm">
            <span className="hidden text-muted-foreground sm:inline">运营管理</span>
            <ChevronRight className="hidden h-4 w-4 text-muted-foreground sm:block" />
            <span className="truncate font-semibold text-[#123b43]">{currentItem.label}</span>
            <Badge variant="secondary" className="hidden sm:inline-flex">管理员</Badge>
          </div>
          <div className="flex items-center gap-4">
            <Button
              variant="outline"
              size="sm"
              onClick={handleLogout}
              className="gap-1.5 text-muted-foreground hover:text-destructive"
              aria-label="退出登录"
            >
              <LogOut className="h-4 w-4" />
              <span className="hidden sm:inline">退出登录</span>
            </Button>
          </div>
        </header>

        <nav className="flex shrink-0 snap-x gap-1 overflow-x-auto border-b bg-card px-3 py-2 lg:hidden" aria-label="管理端导航">
          {menuItems.map((item) => {
            const Icon = item.icon
            const active = location.pathname === item.path
            return (
              <Link
                key={item.path}
                to={item.path}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'flex shrink-0 snap-start items-center gap-1.5 rounded-md px-3 py-2 text-xs font-medium transition-colors',
                  active
                    ? 'bg-primary text-primary-foreground'
                    : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'
                )}
              >
                <Icon className="h-3.5 w-3.5" />
                {item.label}
              </Link>
            )
          })}
        </nav>

        <main className="flex-1 overflow-y-auto p-5 sm:p-7 lg:p-9">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
