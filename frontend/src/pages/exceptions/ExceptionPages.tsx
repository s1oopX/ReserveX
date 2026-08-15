import { ShieldAlert, ArrowLeft, Home } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'

export function Error403() {
  const nav = useNavigate()
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center text-center p-6">
      <div className="flex h-16 w-16 items-center justify-center rounded-full bg-amber-100 text-amber-700 mb-4">
        <ShieldAlert className="h-8 w-8" />
      </div>
      <h1 className="text-2xl font-bold text-foreground">403 无权访问</h1>
      <p className="mt-2 text-sm text-muted-foreground max-w-md">
        您没有访问该页面或资源的权限。如需使用相关功能，请联系管理员或切换具备权限的账号。
      </p>
      <div className="mt-6 flex items-center gap-3">
        <Button variant="outline" onClick={() => nav(-1)} className="gap-1.5">
          <ArrowLeft className="h-4 w-4" />
          返回上一页
        </Button>
        <Button asChild>
          <Link to="/" className="gap-1.5">
            <Home className="h-4 w-4" />
            返回首页
          </Link>
        </Button>
      </div>
    </div>
  )
}

export function Error404() {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center text-center p-6">
      <h1 className="text-6xl font-extrabold text-primary/30">404</h1>
      <h2 className="mt-4 text-xl font-bold text-foreground">页面不存在</h2>
      <p className="mt-2 text-sm text-muted-foreground max-w-md">
        您访问的路径不存在或已被移除，请检查 URL 是否正确。
      </p>
      <div className="mt-6">
        <Button asChild>
          <Link to="/" className="gap-1.5">
            <Home className="h-4 w-4" />
            返回首页
          </Link>
        </Button>
      </div>
    </div>
  )
}

export function Error500({ requestId }: { requestId?: string }) {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center text-center p-6">
      <div className="flex h-16 w-16 items-center justify-center rounded-full bg-destructive/10 text-destructive mb-4">
        <ShieldAlert className="h-8 w-8" />
      </div>
      <h1 className="text-2xl font-bold text-foreground">500 系统异常</h1>
      <p className="mt-2 text-sm text-muted-foreground max-w-md">
        服务器遇到了处理异常，请稍后重试。若多次出现，请向运维提交 Request ID 进行排障。
      </p>
      {requestId && (
        <div className="mt-4">
          <span className="text-xs font-mono text-muted-foreground bg-muted px-3 py-1.5 rounded">
            Request ID: {requestId}
          </span>
        </div>
      )}
      <div className="mt-6">
        <Button onClick={() => window.location.reload()} className="gap-1.5">
          刷新页面
        </Button>
      </div>
    </div>
  )
}
