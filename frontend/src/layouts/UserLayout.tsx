import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom'
import { Calendar, Ticket, BookOpen, User, LogOut } from 'lucide-react'
import { AppLogo } from '@/components/common/AppLogo'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export function UserLayout() {
  const location = useLocation()
  const nav = useNavigate()
  const { logout } = useAuth()

  const handleLogout = async () => {
    await logout()
    nav('/', { replace: true })
  }

  const navItems = [
    { path: '/slots', label: '场次预约', icon: Calendar },
    { path: '/mine', label: '我的预约', icon: Ticket },
    { path: '/notice', label: '预约须知', icon: BookOpen },
    { path: '/profile', label: '个人中心', icon: User },
  ]

  return (
    <div className="min-h-screen flex flex-col bg-slate-50/50 pb-16 md:pb-0">
      <header className="sticky top-0 z-40 border-b border-slate-200/80 bg-background/95 backdrop-blur-md shadow-xs">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-4 sm:px-6 py-3">
          <Link to="/" className="focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-lg">
            <AppLogo subtitle />
          </Link>
          <nav className="hidden md:flex items-center gap-1">
            {navItems.map((item) => {
              const Icon = item.icon
              const active = location.pathname === item.path
              return (
                <Link
                  key={item.path}
                  to={item.path}
                  className={cn(
                    'flex items-center gap-1.5 px-3 py-2 text-sm font-medium rounded-lg transition-all',
                    active
                      ? 'bg-emerald-50 text-emerald-800 font-semibold shadow-xs border border-emerald-200/60'
                      : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
                  )}
                >
                  <Icon className="h-4 w-4" />
                  <span>{item.label}</span>
                </Link>
              )
            })}
            <Button
              variant="ghost"
              size="sm"
              onClick={handleLogout}
              className="ml-2 text-slate-500 hover:text-rose-600 hover:bg-rose-50 gap-1.5 rounded-lg"
              aria-label="退出登录"
            >
              <LogOut className="h-4 w-4" />
              <span>退出</span>
            </Button>
          </nav>
        </div>
      </header>

      <main className="flex-1 mx-auto w-full max-w-5xl px-4 sm:px-6 py-6">
        <Outlet />
      </main>

      <nav className="md:hidden fixed bottom-0 left-0 right-0 z-40 border-t border-slate-200/80 bg-background/95 backdrop-blur-md flex items-center justify-around py-2 px-1 shadow-lg">
        {navItems.map((item) => {
          const Icon = item.icon
          const active = location.pathname === item.path
          return (
            <Link
              key={item.path}
              to={item.path}
              className={cn(
                'flex flex-col items-center justify-center py-1 px-3 min-w-[64px] min-h-[44px] rounded-lg text-xs font-medium transition-colors',
                active ? 'text-emerald-700 font-semibold' : 'text-slate-500 hover:text-slate-900'
              )}
            >
              <Icon className={cn('h-5 w-5 mb-0.5', active && 'stroke-[2.5px]')} />
              <span>{item.label}</span>
            </Link>
          )
        })}
      </nav>
    </div>
  )
}
