export const NOTICE_RULES = [
  {
    num: '一',
    title: '实名制分时段预约制度',
    content:
      '园区全面实行实名制分时段预约准入制度。游客须凭真实有效的二代身份证件信息进行预约。同一有效身份证件每日在公园范围内限成功预约 1 个游览场次。请在提交前认真核对身份信息，信息不符将无法通过现场核验入园。',
  },
  {
    num: '二',
    title: '预约资格即时消耗与爽约机制',
    content:
      '预约成功即锁定当场次游览名额，并即时消耗该身份证件当天的预约资格。若成功预约后未在指定时段到园核销且未提前取消，将记为爽约 1 次。累计爽约达到规定次数，系统将触发黑名单风控限制，暂停后续预约资格。',
    isCritical: true,
  },
  {
    num: '三',
    title: '取消预约规则与名额退改限制',
    content:
      '因行程变更需取消预约的，须在场次开始前通过个人中心提交取消申请。取消成功后，原预约名额立即作废且不退回公共名额池，当日常规预约资格亦不予恢复，当天无法重新提交任何场次的预约申请。',
    isCritical: true,
  },
  {
    num: '四',
    title: '动态凭证核验与防伪安全规范',
    content:
      '入园时须出示本人预约成功后生成的动态入园二维码，并配合出示身份证件原件。入园二维码包含高强度安全加密签名并每 60 秒自动刷新，静态截图、打印件或转让二维码均无法通过闸机及核验设备。',
  },
  {
    num: '五',
    title: '湿地生态保护与入园守则',
    content:
      '公园开放时间为每日 08:30 - 17:30（16:30 停止入园）。园区内禁止携带宠物入内，严禁捕猎鸟类或破坏湿地植被。如遇暴雨、台风等不可抗力恶劣天气导致闭园，已预约名额将由系统统一作废并公告通知。',
  },
]

export function NoticeContent() {
  return (
    <div className="space-y-5 text-sm text-foreground leading-relaxed font-sans select-text">
      {/* Preamble with clean left border accent */}
      <div className="border-l-2 border-primary/60 pl-3 py-0.5 text-xs text-muted-foreground font-medium leading-relaxed">
        为保障景区秩序、游览安全及生态保护，游客在进行名额预约前，请认真阅读并充分理解以下官方协议条款。
      </div>

      {/* Critical Warning Callout - Pure text with left red border accent */}
      <div className="border-l-2 border-destructive pl-3 py-1 space-y-1 text-xs text-destructive">
        <div className="font-bold flex items-center gap-1">
          <span>核心限制条款告知 (请重点关注)</span>
        </div>
        <ul className="list-disc pl-3.5 space-y-0.5 text-[11.5px] text-destructive/90 font-normal">
          <li><strong>预约资格即时消耗：</strong>抢号成功即锁定名额，未入园或超时取消均消耗当天唯一预约资格。</li>
          <li><strong>名额不可返还退补：</strong>预约取消后名额实时作废不退回池中，当天无法再次预约任何场次。</li>
        </ul>
      </div>

      {/* Continuous Clauses Stream - Clean Typography without background cards */}
      <div className="space-y-4 pt-1">
        {NOTICE_RULES.map((rule) => (
          <section key={rule.num} className="space-y-1">
            <h3 className="font-bold text-xs sm:text-sm text-foreground flex items-center gap-2">
              <span className="font-mono text-primary font-bold">第{rule.num}条</span>
              <span>【{rule.title}】</span>
              {rule.isCritical && (
                <span className="text-[10.5px] font-semibold px-1.5 py-0.2 rounded bg-destructive/10 text-destructive border border-destructive/20 font-mono">
                  重要限制
                </span>
              )}
            </h3>
            <p className="text-xs text-muted-foreground leading-relaxed pl-1">
              {rule.content}
            </p>
          </section>
        ))}

        {/* Legal Disclaimer */}
        <section className="pt-3 border-t border-border/60 space-y-1">
          <h3 className="font-bold text-xs text-foreground">【法律声明与合规提醒】</h3>
          <p className="text-[11px] text-muted-foreground leading-relaxed">
            点击“同意并遵守”即表示您已充分理解并接受上述所有条款约束。利用恶意脚本、虚假身份刷票或囤积名额等违法违规行为，公园有权取消预约资格并追究相应责任。
          </p>
        </section>
      </div>
    </div>
  )
}
