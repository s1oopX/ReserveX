import { Bell } from 'lucide-react'
import { ApiUnavailable } from '@/components/common/ApiUnavailable'
import { EmptyState } from '@/components/common/EmptyState'

export default function Notifications() {
  return (
    <div className="space-y-6 max-w-xl mx-auto">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-foreground font-serif">
          消息通知
        </h1>
        <p className="text-xs text-muted-foreground mt-0.5">
          系统广播与个人预约状态推送提醒
        </p>
      </div>

      <ApiUnavailable
        featureName="消息通知"
        description="后端消息通知与推送服务接口尚未接入。上线后将在此展示场次放号提醒与预约状态变更通知。"
      />

      <EmptyState
        icon={<Bell className="h-8 w-8" />}
        title="暂无新通知"
        description="消息通知功能尚未接入，当前没有未读消息。"
      />
    </div>
  )
}
