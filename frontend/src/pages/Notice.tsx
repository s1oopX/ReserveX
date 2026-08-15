import { useNavigate } from 'react-router-dom'
import { ArrowLeft, BookOpen } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { NoticeContent } from '@/components/common/NoticeContent'

export default function Notice() {
  const navigate = useNavigate()

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <div className="flex items-center gap-3 border-b pb-4">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)} aria-label="返回">
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div>
          <h1 className="text-xl font-bold tracking-tight text-foreground font-serif flex items-center gap-2">
            <BookOpen className="h-5 w-5 text-primary" />
            <span>湿地公园预约须知与规则</span>
          </h1>
          <p className="text-xs text-muted-foreground mt-0.5">
            官方游览名额预约规则与到园核验须知
          </p>
        </div>
      </div>

      <Card className="shadow-sm border">
        <CardHeader className="pb-2 border-b bg-muted/20">
          <CardTitle className="text-base font-bold text-foreground">
            须知条款全文
          </CardTitle>
        </CardHeader>
        <CardContent className="p-6">
          <NoticeContent />
        </CardContent>
      </Card>
    </div>
  )
}
