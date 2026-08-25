import { useNavigate } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { NoticeContent } from '@/components/common/NoticeContent'
import { PageHeader } from '@/components/common/PageHeader'

export default function Notice() {
  const navigate = useNavigate()

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <PageHeader
        title="湿地公园预约须知与规则"
        description="了解分时预约、入园凭证和到访安排"
        actions={<Button variant="outline" size="sm" onClick={() => navigate(-1)}><ArrowLeft className="h-4 w-4" />返回</Button>}
      />

      <Card className="overflow-hidden rounded-2xl border-slate-200 bg-white shadow-sm">
        <CardHeader className="border-b border-slate-100 pb-4">
          <CardTitle className="font-serif text-xl font-semibold text-[#123b43]">
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
