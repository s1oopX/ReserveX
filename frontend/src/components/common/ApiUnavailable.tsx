import { Construction } from 'lucide-react'
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'

interface ApiUnavailableProps {
  featureName: string
  description?: string
}

export function ApiUnavailable({ featureName, description }: ApiUnavailableProps) {
  return (
    <Card className="my-6 border-dashed border-amber-300 bg-amber-50/50">
      <CardHeader>
        <div className="flex items-center gap-2">
          <Construction className="h-5 w-5 text-amber-600" />
          <CardTitle className="text-base text-amber-900">{featureName}</CardTitle>
          <Badge variant="warning" className="ml-auto">接口尚未接入</Badge>
        </div>
        <CardDescription className="text-amber-800/80">
          {description || `后端 ${featureName} 接口尚未开发完成，当前页面展示功能架构布局，按钮和数据请求已安全禁用。`}
        </CardDescription>
      </CardHeader>
      <CardContent className="text-xs text-amber-800/70 font-mono bg-amber-100/50 rounded-md p-3">
        System Notice: Endpoint not present in current backend controller specifications. Fake fallback data is strict-prohibited.
      </CardContent>
    </Card>
  )
}
