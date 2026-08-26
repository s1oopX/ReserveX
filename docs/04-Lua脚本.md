# 04 · Lua 脚本

> 全部脚本单机 Redis 下成立(M3:判重+扣减合并 Lua 无跨槽问题)。三类脚本:10.1 抢号、10.2a 创建失败补偿、10.3 放号初始化。取消/过期纯 DB CAS,**无 Lua**(00 §3.1 B 类)。

## 一、Redis Key 模型(全表)

| Key | 类型 | 写入者 | TTL | 用途 |
|---|---|---|---|---|
| `slot:{slotId}:b:{bucketNo}` | String | 10.3 放号 SET / 10.1 抢 DECR / 10.2a INCR | 当日结束 | 桶余量(原子写对象,不回源) |
| `occupy:{rno}` | Hash | 10.1 抢号写满载荷(**不设 TTL**)/ 消费成功删 / 10.2a 删 | **无**(见 §1.3) | 待落库凭证(outbox)+ 窗口期渲染 |
| `dup:{slot_date}:{id_card_hash}` | String | 10.1 SET NX / 10.2a DEL | **`slot_date` 当日 23:59:59 − now**(见 §1.1) | 全日配额判重第一道 |
| `pending:persist` | ZSet | 10.1 ZADD / 消费成功 ZREM / 10.2a ZREM / scanner ZRANGEBYSCORE | 永久(scanner 清理) | 超时未落库索引 |
| `slot:meta:{slotId}` | Hash | 放号/增容主动写 / 单飞重建 | 长 TTL + 抖动 | 场次元数据(穿透防护+列表读源) |
| `slot:meta:absent:{slotId}` | String(空标记) | 查 DB 无 → 写 / `cacheMeta` DEL | 短(60s,`absent-ttl-sec`) | 空值缓存(挡不存在)。⚠️ **独立 key**,不能写在 Hash `slot:meta:{slotId}` 上 —— 类型冲突会让后续 HGETALL 报 WRONGTYPE(05 §1.3) |
| `slot:full:{slotId}` | String | **10.1 Lua 内售罄时 SET**(§1.2)/ 10.2a DEL / 10.3 放号 DEL | 当日结束 | 约满标记(挡真售罄重查) |
| `stats:bucket:{key}:hit` / `stats:borrow:{key}` | String | 10.1 INCR(首次 INCR 时 EXPIRE) | 当日结束(与桶同寿) | 压测埋点 |
| `ratelimit:user:{userId}` / `ratelimit:slot:{slotId}` | String | **10.1 Lua 内** INCR + 首次 EXPIRE(D5) | 1s 固定窗口 | 用户/场次两维限流,折叠进抢号 Lua 保持 2 round-trip |
| ~~`rl:user:{id}` / `rl:slot:{slotId}`~~ | ~~(RRateLimiter)~~ | **已取消** | — | 原计划用 Redisson `RRateLimiter` 做第二层限流。D5 把限流折叠进 10.1 Lua(上一行)后这套不再存在:独立限流器要额外 round-trip,而抢号的硬约束是 2 次。代码中无 `rl:` 前缀 |
| Sa-Token 会话 | — | Sa-Token + Redis token mapping | access 30min / refresh 7d | 鉴权与即时撤销 |

> ⚠️ 单飞重建锁**只用在 `slot:meta` 重建**(05 §二)。桶余量/`slot:full`/`dup` **不套单飞**——它们是原子写对象,套锁破坏 Lua 原子性。

### 1.1 `dup` TTL 必须覆盖到 `slot_date` **当日结束**(☑ P0 修:原"次日0点"是硬 bug)

⚠️ **原缺陷**:原 docs 写 dup TTL = 「次日 0 点 − now」。但 07 §3.1 明确「生成**次日** slot」——即用户**今天**抢**明天**的号。dup key 在今夜 24:00 过期,**恰好在该场次当天开始时消失**,导致:

1. 场次当天该证可再次通过 Lua 第一道(`SET NX dup` 成功)→ DECR 桶 + 写 occupy + 发消息 → 消费者撞 `id_card_route` PK → route 冲突 → 10.2a 回滚。**用户体验 = 抢号成功页,几秒后名额被撤**。
2. 更致命:这使 **route 冲突从"病理态"变成"常态路径"**,而 §三·补·2 全部论证(「route 冲突正常不可能触发,仅在 AOF 丢失时出现」→ 故 `DEL dup` 违 M7 的瑕疵不显现)**连同结论一起失效**——常态下 DEL dup 会真的让人当天多约一个号。

**修正**:`dup_ttl = unix_ts(slot_date 23:59:59) − now`(即覆盖到**该场次所属日期**的结束,而非"明天零点")。

- 今天(8/9)抢明天(8/10)的号 → TTL ≈ 48h 内的余量,dup 活到 8/10 23:59:59;
- 同一 `slot_date` 的多个时段共用同一 dup key(配额是"一天一次",非"一时段一次")→ 语义正确;
- 上限保护:TTL 上限设 7 天(防管理端配了远期 slot 导致 dup 长期占内存),超期由 route PK 第二道兜。

> ☑ 副作用(正向):修正后 route 冲突恢复为"仅 AOF 丢失的病理态",§三·补·2 的论证重新成立。
>
> ⚠️ **`unix_ts(slot_date 23:59:59)` 必须按 `Asia/Shanghai` 算**(08 §7.2):`dup_ttl` 由应用层算好后作为 `ARGV[5]` 传入(Redis 无时区概念,时区错误全部来自算它的那台 JVM)。若 JVM 在 UTC,算出的目标时刻是**北京时间次日 07:59:59** → dup 多活 8h,跨到次日仍在,当日配额判重被"上一天的 key"继续挡住;同理 `slot:full`/桶余量的"当日结束"TTL 也由同一派生口径来,**一处时区错三个 key 全错**。验收断言必须精确到目标时刻 ±60s,**不能只断言"TTL 够长"**——UTC 下算出的秒数更大,`>=` 断言会假绿(09 §6.7 用例 5)。

### 1.2 `slot:full` 的写入者 = **10.1 Lua 内**(☑ P1 修:原 docs 无写入点)

⚠️ **原缺陷**:02 §一 写「Lua 返回 0 + 写 slot:full」,但 §二 的 10.1 脚本里**没有** `SET slot:full`,只有 `DEL dup` + `return 0`;10.3 放号却要 `DEL slot:full`,说明它必须有写入方——写入点在 docs 中丢失。

**裁定:写在 Lua 内**(§二末尾已落地),理由:与"判满"同一原子块。若放应用层(收到 0 后再 `SET`),两步之间若发生增容(§六 INCRBY),会把已增容的 slot 错误标记为满,且该标记 TTL 到当日结束 → **增容出来的名额被永久挡住,无人能约**。

> ⚠️ 对称地,**任何"桶余量从 0 变正"的路径都必须 `DEL slot:full`**:10.2a 补偿回补(§三)、10.3 放号(§四)、§六 增容。三处已全部落地,漏一处即"幽灵售罄"。

### 1.3 `occupy` 刻意**无 TTL**(☑ 修:原"30min + scanner 续期"已废弃)

⚠️ **原设计**:occupy 设 30min TTL,scanner 每轮对仍 pending 的 rno 续期;补投达上限后停止续期。

**问题**:TTL 让"库存已扣但落库未完成"这个事实**会自己消失**。一旦它在补投耗尽、卡单入表、或 Redis 抖动期间过期,`bucket_key` / `dup_key` 就再也拿不到 —— 桶余量永久泄漏,而对账只能报出 diff、修不掉(它不知道该回补哪个桶)。"续期"把正确性押在"scanner 每轮都活着"上,而 scanner 停摆恰恰是最该被兜住的故障。

**裁定**:occupy 是**扣减后的恢复证据**,不设 TTL。删除权只属于两处 —— 成功落库的消费者、补偿脚本 `10.2a`。别处删 = 库存永久泄漏。

- `PendingScanner` 每轮对扫到的 occupy 调 `PERSIST`(兼容升级前带 TTL 的存量 key),而不是续期;
- 转卡单时先断言 occupy 仍存在,再把 `bucket_key`/`dup_key` **抄进 `stuck_reservation`**(03 §八),不把可回滚性押在 Redis 上;
- 代价是 occupy 只能由业务路径回收 —— 这正是想要的:泄漏一条就有一条查得到的证据,而不是静默消失。

## 二、10.1 抢号 + 限流 + 判重 + 借桶(原子)

> ⚠️ 本节代码块与 `backend/src/main/resources/lua/grab.lua` **逐字一致**,改一处必须两处同步。
> `GrabLuaContractTest` 对其中三条形态(occupy 无 TTL、`ARGV[10]=id_card_hash`、借桶上界 `n-2`)有断言。

```lua
-- 返回值契约(调用方按此分支):
--    1  预占成功
--    0  已约满(真售罄,已写 slot:full 标记)
--   -1  今日配额已用(调用方读 dup 字符串复核后可恢复幂等响应)
--   -2  限流命中(user 或 slot 维度超 1 秒固定窗口)
--
-- KEYS[1]      = 命中桶 key (slot:{slot_id}:b:{bucket_no}),bucket_no 由应用层按 03 §4.3 算出
-- KEYS[2..n]   = 借桶扫描 keys,应用层按 (bucket_no+i) % bucket_count 环形顺序排好传入
-- KEYS[n+1]    = ratelimit:user:{userId}      ← D5 折叠进 Lua,保持 2 round-trip
-- KEYS[n+2]    = ratelimit:slot:{slotId}
--   其中 n = bucket_count(借桶数含命中桶)。
-- ⚠️ 末尾两个 KEYS 不是桶 —— 借桶循环上界必须是 n-2,写 #KEYS 会把限流 key 当库存桶读写。
--
-- ARGV[1]  = reservation_no
-- ARGV[2]  = slot_id          ← occupy 满载荷(D1)。⚠️ 旧版误把 userId 写此位,已修
-- ARGV[3]  = userId
-- ARGV[4]  = dup_key (dup:{slot_date}:{id_card_hash})
-- ARGV[5]  = dup_ttl (秒, = slot_date 当日 23:59:59 − now,上限 7 天 —— 见 §1.1)
-- ARGV[6]  = slot_date
-- ARGV[7]  = slot_hour
-- ARGV[8]  = valid_until(ts)   ← 来自 slot.valid_until 固化字段(03 §4.1)
-- ARGV[9]  = id_card_masked
-- ARGV[10] = id_card_hash      ← scanner/stuck 回滚证据。⚠️ 比旧版多插一位,其后全部后移
-- ARGV[11] = create_ts
-- ARGV[12] = pending ZSet key
-- ARGV[13] = slot_full_key (slot:full:{slot_id})            ← ☑ §1.2 补
-- ARGV[14] = slot_full_ttl (秒, = slot_date 当日结束 − now;同时用作 stats 埋点 TTL)
-- ARGV[15] = user_rps  (取 yml ratelimit.user-redis-rps)     ← ☑ D5
-- ARGV[16] = slot_rps  (取 yml ratelimit.slot-redis-rps)     ← ☑ D5

-- 第零道:限流(D5)。1 秒固定窗口:INCR + 首次 EXPIRE。
-- 放在最前:限流命中时不该再动 dup / 桶 / occupy,失败必须干净。
-- Lua 在 Redis 单线程里原子执行,INCR 后到 EXPIRE 之间不会有并发插入 → 无竞态。
local n = #KEYS
local rlUserKey   = KEYS[n - 1]
local rlSlotKey   = KEYS[n]
local userRps = tonumber(ARGV[15])
local slotRps = tonumber(ARGV[16])
local userCnt = redis.call('INCR', rlUserKey)
if userCnt == 1 then redis.call('EXPIRE', rlUserKey, 1) end
if userCnt > userRps then return -2 end
local slotCnt = redis.call('INCR', rlSlotKey)
if slotCnt == 1 then redis.call('EXPIRE', rlSlotKey, 1) end
if slotCnt > slotRps then return -2 end

-- 第一道:全日配额判重(不占库存的快速失败)
if redis.call('SET', ARGV[4], ARGV[1], 'NX', 'EX', ARGV[5]) == false then
    return -1
end

-- occupy():bucketKey = 命中桶完整 key(主桶 KEYS[1] 或借桶 KEYS[i])
-- bucket_no 裸号由完整 key 末段提取:slot:{slot_id}:b:{n} 取末段
local function occupy(bucketKey)
    local bno = string.match(bucketKey, ':(%d+)$')   -- 裸桶号(取消/对账用)
    redis.call('DECR', bucketKey)
    redis.call('HSET', 'occupy:'..ARGV[1],
        'slot_id', ARGV[2], 'slot_date', ARGV[6], 'slot_hour', ARGV[7],
        'valid_until', ARGV[8], 'bucket', bucketKey, 'bucket_no', bno,
        'user_id', ARGV[3], 'id_card_masked', ARGV[9], 'id_card_hash', ARGV[10],
        'create_ts', ARGV[11])
    -- bucket(完整桶 key,10.2a INCR 用)+ bucket_no(裸号)显式双存:
    -- 10.2a 回滚靠 HGET 'bucket' 拿完整 key,少一个就无法回滚到正确的桶
    -- occupy 是扣库存后的恢复证据，只能由成功消费或明确补偿删除。
    redis.call('ZADD', ARGV[12], ARGV[11], ARGV[1]) -- D2 pending 索引
    -- 压测埋点。TTL 与桶同寿(派生自 slot_date 当日结束),不硬编码:
    -- 裸 INCR 会留下永不过期的 key,而 maxmemory-policy=noeviction 下它们只增不减,
    -- 每场次每桶一条永久驻留 —— 这类"埋点把内存吃掉"的泄漏没有任何功能表现。
    --
    -- 用 EXPIRE ... NX(Redis 7.0+)而不是"仅首次 INCR 时 EXPIRE":后者只覆盖
    -- 本次新建的 key,**修不掉修复前已存在的无 TTL 老 key**(它们的 INCR 永远不返 1,
    -- 于是永久驻留)。NX 语义是"没有 TTL 才设",既幂等又能顺手把老 key 收口。
    local hitKey = 'stats:bucket:'..bucketKey..':hit'
    redis.call('INCR', hitKey)
    redis.call('EXPIRE', hitKey, ARGV[14], 'NX')
    return 1
end

local cur = tonumber(redis.call('GET', KEYS[1]) or 0)
if cur > 0 then return occupy(KEYS[1]) end

-- 借桶:按应用层给定的环形顺序扫其他桶。
-- ⚠️ 末尾两个 KEYS 是限流 key(D5),不是桶,循环上界 = n - 2。
for i = 2, n - 2 do
    local other = tonumber(redis.call('GET', KEYS[i]) or 0)
    if other > 0 then
        redis.call('INCR', 'stats:borrow:'..KEYS[i])
        redis.call('EXPIRE', 'stats:borrow:'..KEYS[i], ARGV[14], 'NX')
        return occupy(KEYS[i])
    end
end

-- 全空:真售罄
redis.call('SET', ARGV[13], 1, 'EX', ARGV[14])   -- 写约满标记(与判满同原子,防增容竞态)
redis.call('DEL', ARGV[4])                       -- 回滚判重标记:本次未占号,不应阻塞该证当天重试他场
return 0  -- 已约满
```

> ☑ 已落地:命中桶号由 `string.match(bucketKey, ':(%d+)$')` 从完整 key 推导,**不再用** `tostring(KEYS_idx)` 伪码;`bucket`(完整桶 key)+ `bucket_no`(裸号)显式双存,与 §三 10.2a 的 `HGET 'bucket'` 严格对齐。`slot_id` 字段从误写的 `ARGV[2]` 修正为真正的 slot_id(ARGV[2] 重排为 slot_id,userId 下移至 ARGV[3])。
>
> ☑ 相对最初版本的三处结构变化(**与代码同步于本次审查**):
> 1. **D5 限流折叠进本脚本**:末尾两个 KEYS + `ARGV[15..16]`,限流命中返 `-2` 且**零副作用**(桶不扣、无 occupy、dup 不写)。代价是借桶循环上界从 `#KEYS` 改成 `n-2` —— 这一处写错不会报错,只会让限流 key 被当成库存桶 `GET`/`DECR`。
> 2. **`ARGV[10]` 插入 `id_card_hash`**,其后各位全部后移(create_ts 10→11,pending 11→12,slot_full 12→13,ttl 13→14)。它是 scanner 转卡单时拼 `dup_key` 的唯一来源。
> 3. **occupy 不再 `EXPIRE`**(见 §1.3),原 `ARGV[14]=occupy_ttl` 取消;该位现为 `slot_full_ttl`,并复用为 stats 埋点 TTL。
> 4. **stats 埋点补 TTL**:原先是裸 `INCR`,留下永不过期的 key。`maxmemory-policy=noeviction` 下它们只增不减(每场次每桶各一条),是一条**没有任何功能表现**的内存泄漏。用 `EXPIRE ... NX`(Redis 7.0+)而非"仅首次 INCR 时设" —— 后者只覆盖新建的 key,修不掉修复前遗留的无 TTL 老 key(它们的 `INCR` 永远不返 1)。NX 幂等且顺手收口老 key,已在真 Redis 上验过两种前置态。

> ⚠️ **D3 前置**:本脚本不含 slot 状态校验。已放号/未过期校验在**应用层读 `slot:meta`** 前置完成(05 §一),非法 slot_id 不进本 Lua。

> ⚠️ **KEYS 顺序由应用层负责**:主桶用 `hash(rno) % bucket_count`,借桶按环形顺序 `(bucket_no+1) ... (bucket_no+bucket_count-1)`(03 §4.3)。**若应用层图省事固定从桶 0 排序,低号桶会被反复借空**,09 §三"各桶余量分布均匀"指标必挂——这是隐性 bug,压测能抓到。

## 三、10.2a 创建失败补偿回滚(原子,A 类)

> **触发边界(钉死,防幽灵预约)**:10.2a **仅由消费者在业务失败且 ACK 原消息后**发送的 `CompensateRollback` 驱动。当前唯一业务失败 = `id_card_route` 唯一冲突(该证已被并发请求约走)。
> - **基础设施异常(DB 超时/Redis 闪断/网络)不触发 10.2a** —— 走 06 §二·补「抛异常 → 重投 N 次 → 耗尽进 DLQ → 人工研判(重投 or 回滚)」。自主放弃持久化 = 与 pending-scanner 补投/MQ 重投竞态,见 §三·补。
> - **route 冲突时消费者必须先 CAS 清阶段1 孤儿 reservation**(`status 0→2`,语义"未成立作废",M1 下不返还故不影响 occupied),再发 CompensateRollback,再 ACK 原消息。否则 B 向对账会误判"reservation 在 occupy 删"而重建 occupy,与已回滚冲突。
> 语义 = 预约从未成立,恢复未扣减。

```lua
-- KEYS[1] = 占用时命中的桶 key (从 occupy:{rno}.bucket 读)
-- ARGV[1] = reservation_no
-- ARGV[2] = dup_key
-- ARGV[3] = pending ZSet key
-- ARGV[4] = slot_full_key (slot:full:{slot_id})   ← ☑ P1 补,见下

if redis.call('EXISTS', 'occupy:'..ARGV[1]) == 1 then
    local bucketKey = redis.call('HGET', 'occupy:'..ARGV[1], 'bucket')
    if bucketKey then
        redis.call('INCR', bucketKey)              -- 桶余量 +1(回补,防少约)
        redis.call('DEL', ARGV[4])                 -- ☑ 必须:桶从0变正,清约满标记,否则回补的名额被永久挡住
    end
    redis.call('DEL', 'occupy:'..ARGV[1])         -- 删预占记录
    redis.call('DEL', ARGV[2])                    -- 删 dup(没约成,允许重试;瑕疵见 §三·补·2)
    redis.call('ZREM', ARGV[3], ARGV[1])          -- ☑ 修正:清 pending 索引,否则 scanner 脏扫
    return 1
end
return 0  -- 已回滚过(幂等)
```

> ☑ **P1 修正(`DEL slot:full`)**:原 10.2a 只 `INCR` 桶不清 `slot:full`。若该 slot 已被 10.1 标记售罄(标记 TTL 到当日结束),回补出来的这 1 个名额会被 `slot:full` **永久挡住**——A 类补偿"回补防少约"的目的完全落空(桶里有数但前端显示已满、且 07 §2.3 卡片直接禁用)。补 `DEL slot:full` 后闭合。

> 注意 `occupy:{rno}.bucket` 字段 = 完整桶 key(如 `slot:101:b:3`),10.1 写时已存全(见 §二 occupy());10.2a 直接 `INCR bucketKey`。bucket_no 字段单独存裸号(取消/对账用)。`slot_full_key` 由调用方从 `occupy.slot_id`(或 CompensateRollback 消息体的 `bucket_key` 推导)拼出。

### 三·补 为何 10.2a 不能由"落库超时/消费失败"自主触发(幽灵预约反例)

自主触发的 10.2a 与 pending-scanner 补投 / MQ 重投对同一 rno 意图相反,且会竞态:

```
Lua DECR bucket → MQ 丢/慢
 → 自主 10.2a 触发:INCR 桶 / DEL occupy / DEL dup / ZREM pending
 → 随后 MQ 重投 或 pending-scanner 补投的原 ReservationCreated 被消费
    (consumed_event 未写,因前次消费失败)
 → 消费者读 occupy 已删、无 cancelled 标记 → INSERT status=RESERVED 幽灵预约 + occupied+1
 → 但 Redis 桶已被 10.2a 回补、消费者不再 DECR
结果:Redis 号源比真实多 1(A 偏高)→ 潜在超卖;不变量 C=A+R+V+X 破裂
```

这正是 06 §四「触发一次≠执行一次」在 10.2a 身上的反例。**解法 = 让 retry 与 compensate 意图互斥**:持久化重试永不自主放弃,只靠"可重试(pending-scanner 补投 + MQ 重投)+ 可对账 + 死信人工"收敛;10.2a 只在**业务已判定不可成立(route 冲突)且原消息已 ACK 不会再重投**时触发。pending-scanner 补投设上限(见 02 §三),耗尽转人工而非自主回滚。

### 三·补·2 route 冲突下 DEL dup 的已知瑕疵(诚实标注,不改路线)

⚠️ **瑕疵**:10.2a 末尾 `DEL dup`(脚本内无条件)在 route 冲突语义下不完全正确——route 冲突意味着「该证已被另一并发请求约走成立」,该 dup 代表的是**已成立预约**,DEL 掉会让该人当天能再抢一个号,理论上**违反 M7(一人一天一次)**。这与 10.2a 的「真未成立、允许重试」原设计语义不完全对齐。

**为何不改**(路线裁定,2026-08-09):
1. **route 冲突正常不可能触发**——dup `SET NX` 是 Lua 内原子第一道,同 id_card 的并发请求只会有一个拿到 dup、另一个直接返回 -1,根本到不了 route。route 冲突仅在 **Redis 丢失 dup key**(AOF 丢的病理态,即 06 §6.1 已认下的降一档场景)下才可能出现。
   > ☑ **前提修正(2026-08-09 二轮)**:该论证成立**依赖 dup TTL 覆盖到 slot_date 当日结束**(§1.1)。原"次日 0 点"TTL 会让 dup 在场次当天开始时消失,route 冲突退化为**常态**,本条论证及下面两条全部失效。§1.1 已修,论证恢复成立。
2. **该场景下 dup 本就已丢**——Redis 崩+AOF 丢时 dup 与 occupy 一起丢,10.2a 的 `DEL dup` 作用在已不存在的 key 上是 no-op,瑕疵不实际显现。
3. **不为不可能的 case 加复杂度**:新增「route 冲突保留 dup」的 10.2b 变体(脚本内加 ARGV 标志跳过 DEL dup)虽最正确,但为一个病理态才显现的路径增加脚本变体/参数,grilling 反而会被问"为何为不可能的 case 加变体"——过度设计。

> 口径:route 冲突仍走 10.2a(CAS 清孤儿 reservation + 发 CompensateRollback + ACK),`DEL dup` 的 M7 违反瑕疵在「dup 已丢」前提下不显现,接受。简历/面试若被深挖 route 冲突路径,诚实答此标注,不为它另立脚本。这与项目整体诚门口径一致(06 §6.1 主动认降级点,非掩盖)。

### 三·补·3 补偿链路的可测性(诚实标注:常态不可达)

⚠️ 承上:route 冲突在正常运行中**不可达**,而它是 10.2a 的**唯一**触发源(§三)。这意味着 B 方案"可重试 + 可补偿 + 可对账"三根柱子里,**补偿这根在常态下永不执行**——06 §6.1 故障矩阵中「消费业务异常(route冲突)→ ✅ 最终一致」实际是一条冷路径。

**这不是缺陷,但必须能证明它真能跑**(面试必问"你的补偿代码实测跑过吗"):

| 手段 | 落点 |
|---|---|
| **故障注入构造 route 冲突** | 测试环境:抢号成功后**手工 `DEL dup:{date}:{hash}`**,再用同一 id_card 抢第二次 → 第二次通过 Lua 第一道 → 消费者必撞 route PK → 触发完整 10.2a 链路(09 §六已列此项,此处给出构造方法) |
| **单测直击 10.2a** | Redisson `RScript` 直接执行 compensate.lua,断言:桶+1 / occupy 删 / dup 删 / ZREM / **slot:full 删**(五项全查,漏一项即上面的 P1 bug 复现) |
| **集成测试断言不变量** | 构造冲突后校验 `C = A+R+V+X`(01 §四)仍成立,且 `reservation.status=2`(孤儿已清) |

> 面试口径:「补偿路径在正常运行下不可达——因为 dup 的 SET NX 在 Lua 内就把并发同证挡住了,route 冲突只在 Redis 丢 key 的病理态出现。所以我用故障注入(手工删 dup)构造该场景做验证,并有单测直击 10.2a 脚本断言五项副作用。**不可达 ≠ 不用写,只是它属于冷路径,必须靠注入测试而非压测覆盖。**」这比"我实现了补偿"强,也比被问倒强。


## 四、10.3 放号初始化桶

```lua
-- KEYS[1..N] = 桶 keys(按 bucket_no 0..N-1 顺序传入)
-- ARGV[1..N] = 各桶初始值,与 KEYS 一一对应
--              ☑ 修:不再是"每桶同一值",按 03 §4.2 余数规则前 rem 个桶多 1
--              (capacity=55/bucket=10 → 6,6,6,6,6,5,5,5,5,5,Σ=55)
-- ARGV[N+1]  = slot_id
-- ARGV[N+2]  = bucket_ttl(秒, = slot_date 当日结束 − now)

local n = #KEYS
for i = 1, n do
    redis.call('SET', KEYS[i], ARGV[i], 'EX', ARGV[n + 2])   -- ☑ 补 TTL(§一 表:桶余量 TTL=当日结束)
end
redis.call('DEL', 'slot:full:'..ARGV[n + 1])                 -- 清约满标记(此时段开始可约了)
return 1
```

> ☑ **两处修正**:① 逐桶传值(原 `ARGV[1]` 一个值给所有桶会丢余数,使 Σ桶初值 < capacity,库存不变量 `C=A+R+V+X` 永久带固定差 —— 见 03 §4.2);② 桶 key 补 `EX`(原脚本 `SET` 无 TTL,但 §一 key 表声明桶余量 TTL = 当日结束,不设则 key 永久驻留,过期场次的桶余量长期占内存且被库存对账当作有效数据比对)。

> ⚠️ **幂等**:`SET` 覆盖写,重跑得同一结果(06 §4.3「10.3 SET 覆盖幂等」)。但**重跑会覆盖掉已被抢掉的余量** → 故 10.3 **必须**在 `released 0→1` CAS 成功后才执行(07 §3.1),CAS 受影响 0 行即说明已放过号,**绝不能再跑 10.3**。这是"锁减概率、CAS 保正确"在放号链路的落点。

## 五、取消/过期(无 Lua,B 类)

M1 不返还 + M7 不改约 → **完全不碰 Redis 桶**:

```sql
-- 主动取消(应用层校验 valid_until 未到前置)
UPDATE reservation SET status=2, version=version+1, update_at=NOW()
WHERE reservation_no=? AND status=0 AND valid_until>=NOW();

-- 过期(定时任务批量扫)
UPDATE reservation SET status=3, version=version+1, update_at=NOW()
WHERE slot_id=? AND status=0 AND valid_until<NOW();
```

> ☑ **CAS 条件统一口径(P2 修)**:取消/过期**均不带 `version=?`**,只靠 `status=0` + `valid_until` 守卫。理由:
> - 窗口期取消(02 §四)DB 里还没记录,**读不到 version**,带 version 的写法在该路径根本无法构造;
> - `status=0` 已是充分守卫(状态机只有 0 能出边),`version` 在此处不提供额外并发保护,只增加"必须先读一次"的往返;
> - 批量过期是 `WHERE slot_id=?` 多行更新,本就无法逐行带 version。
>
> **只有核销带 `version=?`**(01 §3.2):核销是 STAFF 在页面上先看到预约详情(已读到 version)再点核销,带 version 能挡"页面数据已过期"的误操作,语义有价值。**这个不对称是刻意的,不是漏写**——01 §3.2 与本节已对齐。

`occupy:{rno}` 由消费者成功后 `DEL`(**没有"TTL 自然清"这条路** —— occupy 无 TTL,见 §1.3:只有成功落库与 10.2a 两个删除者);M1 下取消/过期无需额外清 occupy——预约已落库,B 类路径读 DB 不读 occupy。dup 不删(TTL 自然过期,§1.1)。`slot:full` 不删(名额不返还,没有新名额可约)。

> ⚠️ **B 类与 A 类的 `slot:full` 处理刚好相反**,别搞混:A 类(10.2a)回补桶 → **必须 DEL slot:full**;B 类(取消/过期)不回补桶 → **不能 DEL slot:full**(删了会让前端显示"可约"但实际桶为 0,用户点进去必失败)。判据统一为:**「桶余量是否从 0 变正」——是则删标记,否则不动。**

## 六、增容 Lua(管理端,9 约束一)

```lua
-- KEYS[1..N] = 该 slot 全部桶 keys(按 bucket_no 顺序)
-- ARGV[1..N] = 各桶增量,与 KEYS 一一对应
--              ☑ 修:按 03 §4.2 余数规则,d_base=delta/N,前 delta%N 个桶多 1(非各桶同值)
-- ARGV[N+1]  = slot_id

local n = #KEYS
for i = 1, n do
    redis.call('INCRBY', KEYS[i], ARGV[i])
end
redis.call('DEL', 'slot:full:'..ARGV[n + 1])    -- ☑ 必须:增容后桶从0变正,清约满标记
return 1
-- 应用层配合:HSET slot:meta:{slotId} capacity {新值} + UPDATE slot capacity CAS + audit_log
```

> ☑ **两处修正**:① 逐桶传增量(原 `ARGV[1]` 均摊会丢 `delta % bucket_count` 的余数,增容 5 个到 10 桶时 `5/10=0` → **一个名额都没加上**,DB capacity 却已 +5,库存对账立刻报差);② 补 `DEL slot:full`(增容的典型场景就是"约满了运营加名额",不清标记则加了也约不到 —— 这是最容易在演示时被抓到的 bug)。

## 七、Lua 脚本清单与调用方对照(编码索引)

| 脚本文件(08 §一 `lua/`) | 章节 | 调用方 | KEYS | ARGV | 返回 |
|---|---|---|---|---|---|
| `grab.lua` | §二 10.1 | ReservationService(抢号) | 命中桶 + 环形借桶序 | 14 项(见 §二) | 1成功/0售罄/-1配额已用 |
| `compensate.lua` | §三 10.2a | rollback-consumer | 命中桶(实际从 occupy 读,KEYS 仅占位) | 4 项 | 1回滚/0已回滚 |
| `release.lua` | §四 10.3 | release-consumer | 全部桶 | N+2 项 | 1 |
| `incr.lua` | §六 增容 | AdminSlotService | 全部桶 | N+1 项 | 1 |

> ⚠️ 四个脚本**全部用 Redisson `RScript.eval` + SHA 缓存**(`scriptLoad` 预加载,`evalSha` 调用),不用每次传全文。脚本变更须清 SHA 缓存(`SCRIPT FLUSH` 或重启)——**编码红线:改了 lua 文件必须重启 backend,否则跑的还是旧脚本**,这在联调期最容易踩。
