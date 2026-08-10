-- ============================================================================
-- 10.1 抢号 + 判重 + 借桶(原子)—— 04 §二 逐字落地
--
-- 返回值契约(调用方按此分支,不要改动):
--    1  预占成功
--    0  已约满(真售罄,已写 slot:full 标记)
--   -1  今日配额已用(同一证件当天已约过)
--
-- ⚠️ 抢号只动 Redis(Q1-B),此脚本内**不得出现任何 DB 语义**。
-- ⚠️ KEYS[1] 是命中桶,KEYS[2..] 是借桶扫描顺序,由应用层按
--    (bucket_no + i) % bucket_count **环形**排好传入(03 §4.3)。
--    环形顺序错了不会报错,只会让借桶偏向固定几个桶 → 倾斜。
--
-- KEYS[1]   = 命中桶 key (slot:{slot_id}:b:{bucket_no})
-- KEYS[2..] = 借桶扫描 keys(环形顺序)
-- ARGV[1]  = reservation_no
-- ARGV[2]  = slot_id
-- ARGV[3]  = userId
-- ARGV[4]  = dup_key (dup:{slot_date}:{id_card_hash})
-- ARGV[5]  = dup_ttl (秒, = slot_date 当日 23:59:59 − now,上限 7 天)
-- ARGV[6]  = slot_date
-- ARGV[7]  = slot_hour
-- ARGV[8]  = valid_until(ts)   ← 来自 slot.valid_until 固化字段
-- ARGV[9]  = id_card_masked
-- ARGV[10] = create_ts
-- ARGV[11] = pending ZSet key
-- ARGV[12] = slot_full_key (slot:full:{slot_id})
-- ARGV[13] = slot_full_ttl (秒, = slot_date 当日结束 − now)
-- ARGV[14] = occupy_ttl (秒, 默认 1800,取 yml pending.occupy-ttl-sec)
-- ============================================================================

-- 第一道:全日配额判重(不占库存的快速失败)
if redis.call('SET', ARGV[4], ARGV[1], 'NX', 'EX', ARGV[5]) == false then
    return -1  -- 今日配额已用
end

-- occupy():bucketKey = 命中桶完整 key(主桶 KEYS[1] 或借桶 KEYS[i])
-- bucket_no 裸号由完整 key 末段提取:slot:{slot_id}:b:{n} 取末段
local function occupy(bucketKey)
    local bno = string.match(bucketKey, ':(%d+)$')   -- 裸桶号(取消/对账用)
    redis.call('DECR', bucketKey)
    redis.call('HSET', 'occupy:'..ARGV[1],
        'slot_id', ARGV[2], 'slot_date', ARGV[6], 'slot_hour', ARGV[7],
        'valid_until', ARGV[8], 'bucket', bucketKey, 'bucket_no', bno,
        'user_id', ARGV[3], 'id_card_masked', ARGV[9], 'create_ts', ARGV[10])
    -- bucket(完整桶 key,10.2a INCR 用)+ bucket_no(裸号)显式双存:
    -- 10.2a 回滚靠 HGET 'bucket' 拿完整 key,少一个就无法回滚到正确的桶
    redis.call('EXPIRE', 'occupy:'..ARGV[1], ARGV[14])
    redis.call('ZADD', ARGV[11], ARGV[10], ARGV[1]) -- D2 pending 索引
    redis.call('INCR', 'stats:bucket:'..bucketKey..':hit')  -- 压测埋点
    return 1
end

local cur = tonumber(redis.call('GET', KEYS[1]) or 0)
if cur > 0 then return occupy(KEYS[1]) end

-- 借桶:按应用层给定的环形顺序扫其他桶
for i = 2, #KEYS do
    local other = tonumber(redis.call('GET', KEYS[i]) or 0)
    if other > 0 then
        redis.call('INCR', 'stats:borrow:'..KEYS[i])
        return occupy(KEYS[i])
    end
end

-- 全空:真售罄
redis.call('SET', ARGV[12], 1, 'EX', ARGV[13])   -- 写约满标记(与判满同原子,防增容竞态)
redis.call('DEL', ARGV[4])                       -- 回滚判重标记:本次未占号,不应阻塞该证当天重试他场
return 0  -- 已约满
