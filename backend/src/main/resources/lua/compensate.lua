-- ============================================================================
-- 10.2a 创建失败补偿回滚(原子,A 类)—— 04 §三 逐字落地
--
-- 返回值:1 本次回滚 / 2 已回滚(幂等) / 0 未找到可证明的回滚对象
--
-- ⚠️ **调用权限极窄**:只有两处可以执行本脚本 —— 落库路径确认失败的 rollback-consumer,
--    以及人工研判后的卡单回滚。**禁止由"落库超时/消费失败"自主触发**:
--    超时不等于失败,落库可能已成功,自主回滚会造出"DB 有预约但 Redis 余量已回补"
--    的幽灵预约,而库存对账会把它算成 diff 却修不掉(04 §三·补)。
--
-- ⚠️ occupy 的删除权只属于两处:消费者落库成功、本脚本。别处删 = 库存永久泄漏。
--
-- KEYS[1] = 占用时命中的桶 key(occupy 丢失时使用的持久消息/卡单兜底证据)
-- ARGV[1] = reservation_no
-- ARGV[2] = dup_key
-- ARGV[3] = pending ZSet key
-- ARGV[4] = slot_full_key (slot:full:{slot_id})
-- ============================================================================

local doneKey = 'rollback:done:'..ARGV[1]
if redis.call('EXISTS', doneKey) == 1 then
    return 2
end

local bucketKey = KEYS[1]
if redis.call('EXISTS', 'occupy:'..ARGV[1]) == 1 then
    -- 从 occupy 读回**真实**命中的桶:借桶时它不等于 KEYS[1]。
    -- 用 KEYS[1] 回补会把余量还给错的桶 —— 总数对、分布错,单桶可能超卖
    local occupiedBucket = redis.call('HGET', 'occupy:'..ARGV[1], 'bucket')
    if occupiedBucket and occupiedBucket ~= '' then
        bucketKey = occupiedBucket
    end
end
if not bucketKey or bucketKey == '' then
    return 0
end

-- 桶和 dup 同在场次日结束时过期。桶若意外丢失，INCR 会重建无 TTL 的永久历史库存；
-- 先从 dup/meta 取仍可信的剩余 TTL，过期场次只做业务收口，不重建已失效库存。
local recoveryTtl = redis.call('PTTL', ARGV[2])
local slotId = string.match(bucketKey, '^slot:(%d+):b:%d+$')
if slotId then
    local metaTtl = redis.call('PTTL', 'slot:meta:'..slotId)
    if metaTtl > recoveryTtl then recoveryTtl = metaTtl end
end
if redis.call('EXISTS', bucketKey) == 1 then
    -- A permanent bucket with no trustworthy lifecycle is corrupt. Keep the
    -- rollback pending for repair instead of preserving it forever.
    if redis.call('PTTL', bucketKey) < 0 and recoveryTtl <= 0 then
        return 0
    end
    redis.call('INCR', bucketKey)
    if redis.call('PTTL', bucketKey) < 0 and recoveryTtl > 0 then
        redis.call('PEXPIRE', bucketKey, recoveryTtl)
    end
elseif recoveryTtl > 0 then
    redis.call('SET', bucketKey, 1, 'PX', recoveryTtl)
end
redis.call('DEL', ARGV[4])   -- 必须:桶从 0 变正,清约满标记,否则回补的名额被永久挡住
redis.call('DEL', 'occupy:'..ARGV[1])   -- 删预占记录(不存在也幂等)
if redis.call('GET', ARGV[2]) == ARGV[1] then
    redis.call('DEL', ARGV[2])          -- 只删本预约的 dup,不误删后来生成的新占位
end
redis.call('ZREM', ARGV[3], ARGV[1])    -- 清 pending 索引,否则 scanner 脏扫
redis.call('SET', doneKey, '1')         -- 永久幂等证据,rno 不复用
return 1
