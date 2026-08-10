-- ============================================================================
-- 10.2a 创建失败补偿回滚(原子,A 类)—— 04 §三 逐字落地
--
-- 返回值:1 已回滚 / 0 occupy 不存在(幂等:已回滚过或从未预占)
--
-- ⚠️ **调用权限极窄**:只有两处可以执行本脚本 —— 落库路径确认失败的 rollback-consumer,
--    以及人工研判后的卡单回滚。**禁止由"落库超时/消费失败"自主触发**:
--    超时不等于失败,落库可能已成功,自主回滚会造出"DB 有预约但 Redis 余量已回补"
--    的幽灵预约,而库存对账会把它算成 diff 却修不掉(04 §三·补)。
--
-- ⚠️ occupy 的删除权只属于两处:消费者落库成功、本脚本。别处删 = 库存永久泄漏。
--
-- KEYS[1] = 占用时命中的桶 key(仅为声明访问的 key;真实桶从 occupy 读,见下)
-- ARGV[1] = reservation_no
-- ARGV[2] = dup_key
-- ARGV[3] = pending ZSet key
-- ARGV[4] = slot_full_key (slot:full:{slot_id})
-- ============================================================================

if redis.call('EXISTS', 'occupy:'..ARGV[1]) == 1 then
    -- 从 occupy 读回**真实**命中的桶:借桶时它不等于 KEYS[1]。
    -- 用 KEYS[1] 回补会把余量还给错的桶 —— 总数对、分布错,单桶可能超卖
    local bucketKey = redis.call('HGET', 'occupy:'..ARGV[1], 'bucket')
    if bucketKey then
        redis.call('INCR', bucketKey)
        redis.call('DEL', ARGV[4])   -- 必须:桶从 0 变正,清约满标记,否则回补的名额被永久挡住
    end
    redis.call('DEL', 'occupy:'..ARGV[1])   -- 删预占记录
    redis.call('DEL', ARGV[2])              -- 删 dup(没约成,允许重试;瑕疵见 04 §三·补·2)
    redis.call('ZREM', ARGV[3], ARGV[1])    -- 清 pending 索引,否则 scanner 脏扫
    return 1
end
return 0
