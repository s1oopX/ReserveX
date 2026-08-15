import { Link, useNavigate } from 'react-router-dom'
import { User, KeyRound, BookOpen, LogOut } from 'lucide-react'
import { authApi } from '@/api/auth'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'

export default function Profile() {
  const nav = useNavigate()
  const role = authApi.currentRole() || 'USER'

  const handleLogout = async () => {
    await authApi.logout()
    nav('/login')
  }

  const roleText = role === 'ADMIN' ? '超级管理员' : role === 'STAFF' ? '核销人员' : '游客用户'

  return (
    <div className="space-y-6 max-w-xl mx-auto">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-foreground font-serif">
          个人中心
        </h1>
        <p className="text-xs text-muted-foreground mt-0.5">
          账号身份与安全设置
        </p>
      </div>

      <Card className="shadow-2xs border">
        <CardHeader className="pb-3 border-b bg-muted/20">
          <div className="flex items-center gap-3">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-primary/10 text-primary font-bold text-lg">
              <User className="h-6 w-6" />
            </div>
            <div>
              <CardTitle className="text-base font-bold text-foreground">
                ReserveX 用户
              </CardTitle>
              <div className="flex items-center gap-2 mt-1">
                <Badge variant="secondary" className="text-xs">
                  {roleText}
                </Badge>
                <span className="text-xs font-mono text-muted-foreground">ROLE: {role}</span>
              </div>
            </div>
          </div>
        </CardHeader>

        <CardContent className="p-6 space-y-4">
          <div className="space-y-2">
            <h3 className="text-xs font-semibold text-muted-foreground uppercase">安全与偏好</h3>
            <div className="rounded-lg border divide-y">
              <Link
                to="/change-password"
                className="flex items-center justify-between p-3.5 text-sm hover:bg-accent transition-colors"
              >
                <div className="flex items-center gap-2.5">
                  <KeyRound className="h-4 w-4 text-primary" />
                  <span>修改账号密码</span>
                </div>
                <span className="text-xs text-muted-foreground">定期更换密码</span>
              </Link>

              <Link
                to="/notice"
                className="flex items-center justify-between p-3.5 text-sm hover:bg-accent transition-colors"
              >
                <div className="flex items-center gap-2.5">
                  <BookOpen className="h-4 w-4 text-primary" />
                  <span>公园预约须知</span>
                </div>
                <span className="text-xs text-muted-foreground">规则说明</span>
              </Link>
            </div>
          </div>

          <div className="pt-2">
            <Button
              variant="outline"
              onClick={handleLogout}
              className="w-full gap-2 text-destructive border-destructive/30 hover:bg-destructive/10"
            >
              <LogOut className="h-4 w-4" />
              <span>安全退出登录</span>
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
