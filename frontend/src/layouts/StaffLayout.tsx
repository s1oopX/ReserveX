import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom'
import { QrCode, LayoutDashboard, CalendarCheck, LogOut, ArrowLeftRight } from 'lucide-react'
import { AppLogo } from '@/components/common/AppLogo'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

export function StaffLayout() {
  const location = useLocation()
  const nav = useNavigate()
  const { role, logout } = useAuth()
  const isAdmin = role === 'ADMIN'

  const handleLogout = async () => {
    await logout()
    nav('/login', { replace: true })
  }

  const navItems = [
    { path: '/staff/today', label: '今日工作台', icon: LayoutDashboard },
    { path: '/staff/verify', label: '扫码核销', icon: QrCode },
    { path: '/staff/reservations', label: '今日预约', icon: CalendarCheck },
  ]

  return (
    <div className="min-h-screen flex flex-col bg-background">
      <header className="sticky top-0 z-40 border-b bg-card shadow-xs">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-3.5">
          <div className="flex items-center gap-3">
            <AppLogo />
            <Badge variant="secondary" className="bg-emerald-100 text-emerald-800 border-emerald-300 font-medium">
              核销端
            </Badge>
          </div>

          <nav className="flex items-center gap-2">
            {navItems.map((item) => {
              const Icon = item.icon
              const active = location.pathname === item.path
              return (
                <Link
                  key={item.path}
                  to={item.path}
                  className={cn(
                    'flex items-center gap-2 px-4 py-2.5 min-h-[44px] text-sm font-semibold rounded-lg transition-colors',
                    active
                      ? 'bg-primary text-primary-foreground shadow-xs'
                      : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'
                  )}
                >
                  <Icon className="h-4 w-4" />
                  <span>{item.label}</span>
                </Link>
              )
            })}
          </nav>

          <div className="flex items-center gap-3">
            {isAdmin && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => nav('/admin/dashboard')}
                className="min-h-[44px] border-primary/40 text-primary hover:bg-primary/10 gap-1.5 font-medium"
              >
                <ArrowLeftRight className="h-4 w-4" />
                <span>返回管理端</span>
              </Button>
            )}
            <Button
              variant="ghost"
              size="sm"
              onClick={handleLogout}
              className="min-h-[44px] text-muted-foreground hover:text-destructive gap-1.5"
              aria-label="退出登录"
            >
              <LogOut className="h-4 w-4" />
              <span>退出</span>
            </Button>
          </div>
        </div>
      </header>

      <main className="flex-1 mx-auto w-full max-w-6xl px-6 py-6">
        <Outlet />
      </main>
    </div>
  )
}
