-- ============================================================================
-- 增容(管理端,9 约束一)—— 04 §六 逐字落地
--
-- 返回值:1 本次增容 / 2 已应用(幂等) / 0 version 不匹配
--
-- ⚠️ ARGV[1..N] 是**逐桶增量**,不是"总增量均摊"。按 03 §4.2 余数规则:
--    d_base = delta / N,**前 delta % N 个桶各多 1**。
--    若传"各桶同值 delta/N":增容 5 个到 10 桶时整数除法得 0 → **一个名额都没加上**,
--    而 DB 的 capacity 已经 +5,库存对账立刻报差,且现象是"运营加了名额但还是约不到"。
--
-- ⚠️ DEL slot:full 必须有:增容的典型场景就是"约满了运营加名额",
--    不清标记则加了也约不到 —— 这是最容易在演示时被当场抓到的 bug。
--    (对称纪律:任何"桶余量从 0 变正"的路径都要 DEL slot:full ——
--     10.2a 补偿、10.3 放号、本脚本,三处一个都不能漏。)
--
-- ⚠️ 只增不减:容量减少无法表达(已被抢走的名额收不回),管理端须拦在接口层。
--
-- 应用层配合(三件,缺一即不一致):
--    HSET slot:meta:{slotId} capacity {新值}   ← 否则列表页显示的还是旧容量
--    UPDATE slot SET capacity=? ... CAS        ← DB 侧账
--    audit_log(action='INCREASE_CAPACITY')     ← 谁加的、加了多少
--
-- KEYS[1..N] = 该 slot 全部桶 keys(按 bucket_no 顺序)
-- ARGV[1..N] = 各桶增量,与 KEYS 一一对应
-- ARGV[N+1]  = slot_id
-- ARGV[N+2]  = expected capacity version
-- ARGV[N+3]  = new capacity version
-- ARGV[N+4]  = new capacity
-- ============================================================================

local n = #KEYS
local versionKey = 'slot:capacity:version:'..ARGV[n + 1]
local applied = redis.call('GET', versionKey)
local ttl = redis.call('PTTL', versionKey)
if ttl <= 0 then
    return 0
end
for i = 1, n do
    -- A missing bucket cannot be reconstructed from the delta because its
    -- previous remaining stock is unknown.
    if redis.call('EXISTS', KEYS[i]) == 0 then
        return 0
    end
end
if applied == ARGV[n + 3] then
    return 2
end
if applied ~= ARGV[n + 2] then
    return 0
end
for i = 1, n do
    redis.call('INCRBY', KEYS[i], ARGV[i])
    -- versionKey 是放号时同批设置的 TTL 门闩；缺桶重建后必须继承它的生命周期。
    if redis.call('PTTL', KEYS[i]) < 0 and ttl > 0 then
        redis.call('PEXPIRE', KEYS[i], ttl)
    end
end
redis.call('DEL', 'slot:full:'..ARGV[n + 1])   -- 必须:增容后桶从 0 变正,清约满标记
local metaKey = 'slot:meta:'..ARGV[n + 1]
redis.call('HSET', metaKey, 'capacity', ARGV[n + 4])
if redis.call('PTTL', metaKey) < 0 and ttl > 0 then
    redis.call('PEXPIRE', metaKey, ttl)
end
redis.call('SET', versionKey, ARGV[n + 3], 'KEEPTTL')
return 1
