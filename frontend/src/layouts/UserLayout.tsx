import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom'
import { Bell, Calendar, Ticket, BookOpen, User, LogOut } from 'lucide-react'
import { AppLogo } from '@/components/common/AppLogo'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export function UserLayout() {
  const location = useLocation()
  const nav = useNavigate()
  const { logout } = useAuth()
  const navItems = [
    { path: '/slots', label: '场次预约', icon: Calendar },
    { path: '/mine', label: '我的预约', icon: Ticket },
    { path: '/notice', label: '预约须知', icon: BookOpen },
    { path: '/profile', label: '个人中心', icon: User },
  ]

  const handleLogout = async () => {
    await logout()
    nav('/', { replace: true })
  }

  const isActive = (path: string) => location.pathname === path || location.pathname.startsWith(`${path}/`) || (path === '/mine' && location.pathname.startsWith('/reservation/'))

  return (
    <div className="app-shell flex min-h-screen flex-col pb-20 md:pb-0">
      <header className="sticky top-0 z-40 border-b border-slate-200/80 bg-background/90 shadow-sm backdrop-blur">
        <div className="mx-auto flex h-[72px] max-w-7xl items-center justify-between px-4 sm:px-6 lg:px-8">
          <Link to="/" className="rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><AppLogo subtitle /></Link>
          <Link
            to="/notifications"
            aria-current={location.pathname === '/notifications' ? 'page' : undefined}
            aria-label="消息通知"
            className={cn(
              'flex h-10 w-10 items-center justify-center rounded-lg md:hidden',
              location.pathname === '/notifications' ? 'bg-primary/10 text-primary' : 'text-muted-foreground'
            )}
          >
            <Bell className="h-5 w-5" />
          </Link>
          <nav className="hidden items-center gap-1 md:flex">
            {navItems.map(({ path, label, icon: Icon }) => {
              const active = isActive(path)
              return <Link key={path} to={path} aria-current={active ? 'page' : undefined} className={cn('flex items-center gap-2 rounded-lg px-3.5 py-2.5 text-sm font-medium transition-colors', active ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-muted hover:text-foreground')}><Icon className="h-4 w-4" />{label}</Link>
            })}
            <Link
              to="/notifications"
              aria-current={location.pathname === '/notifications' ? 'page' : undefined}
              aria-label="消息通知"
              title="消息通知"
              className={cn(
                'ml-1 flex h-10 w-10 items-center justify-center rounded-lg transition-colors',
                location.pathname === '/notifications'
                  ? 'bg-primary/10 text-primary'
                  : 'text-muted-foreground hover:bg-muted hover:text-foreground'
              )}
            >
              <Bell className="h-4 w-4" />
            </Link>
            <Button variant="ghost" size="sm" onClick={handleLogout} className="ml-2 gap-1.5 text-muted-foreground hover:text-destructive"><LogOut className="h-4 w-4" />退出</Button>
          </nav>
        </div>
      </header>
      <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6 sm:py-10 lg:px-8"><Outlet /></main>
      <nav className="fixed inset-x-0 bottom-0 z-40 flex items-center justify-around border-t border-slate-200 bg-background/95 px-1 pb-[calc(0.5rem+env(safe-area-inset-bottom))] pt-2 shadow-[0_-8px_24px_rgba(18,59,67,0.06)] md:hidden">
        {navItems.map(({ path, label, icon: Icon }) => {
          const active = isActive(path)
          return <Link key={path} to={path} aria-current={active ? 'page' : undefined} className={cn('flex min-h-11 min-w-16 flex-col items-center justify-center rounded-md px-2 py-1 text-xs', active ? 'font-semibold text-primary' : 'text-muted-foreground')}><Icon className="mb-0.5 h-5 w-5" /><span>{label}</span></Link>
        })}
      </nav>
    </div>
  )
}
