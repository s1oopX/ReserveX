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
    { path: '/admin/dashboard', label: '数据驾驶舱', icon: LayoutDashboard },
    { path: '/admin/templates', label: '场次模板', icon: Clock },
    { path: '/admin/slots', label: '场次日历', icon: CalendarDays },
    { path: '/admin/release-monitor', label: '发布监控', icon: Activity },
    { path: '/admin/reservations', label: '预约管理', icon: TicketCheck },
    { path: '/admin/reconcile', label: '对账中心', icon: Scale },
    { path: '/admin/staff', label: '员工管理', icon: Users },
    { path: '/staff/verify', label: '核销工作台', icon: QrCode },
  ]

  const currentItem = menuItems.find((m) => m.path === location.pathname) || {
    label: '运营管理',
  }

  return (
    <div className="min-h-screen flex bg-background">
      <aside className="w-64 border-r bg-card flex flex-col shrink-0">
        <div className="p-6 border-b">
          <AppLogo subtitle />
        </div>
        <nav className="flex-1 p-4 space-y-1.5 overflow-y-auto">
          {menuItems.map((item) => {
            const Icon = item.icon
            const active = location.pathname === item.path
            return (
              <Link
                key={item.path}
                to={item.path}
                className={cn(
                  'flex items-center gap-3 px-3.5 py-2.5 rounded-lg text-sm font-medium transition-colors group',
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
        <div className="p-4 border-t text-xs text-muted-foreground">
          ReserveX Admin v1.0
        </div>
      </aside>

      <div className="flex-1 flex flex-col min-w-0">
        <header className="h-16 border-b bg-card px-8 flex items-center justify-between shrink-0">
          <div className="flex items-center gap-3">
            <h1 className="text-lg font-bold tracking-tight text-foreground">
              {currentItem.label}
            </h1>
            <Badge variant="secondary" className="bg-emerald-100 text-emerald-800 border-emerald-300">
              超级管理员
            </Badge>
          </div>
          <div className="flex items-center gap-4">
            <span className="text-xs text-muted-foreground font-mono">
              Role: ADMIN
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={handleLogout}
              className="gap-1.5 text-muted-foreground hover:text-destructive"
              aria-label="退出登录"
            >
              <LogOut className="h-4 w-4" />
              <span>退出登录</span>
            </Button>
          </div>
        </header>

        <main className="flex-1 p-8 overflow-y-auto">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
