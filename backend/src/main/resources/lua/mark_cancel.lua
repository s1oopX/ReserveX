-- Atomically marks an in-flight reservation as cancelled.
-- KEYS[1] = occupy:{reservation_no}
-- ARGV[1] = expected user_id
-- ARGV[2] = cancel request_id
-- ARGV[3] = cancelled_at epoch second
-- Returns 1 when marked/already marked, 0 when evidence is missing,
-- -1 when the owner differs, 2 when cancellation is already too late.

local owner = redis.call('HGET', KEYS[1], 'user_id')
if not owner then
    return 0
end
if owner ~= ARGV[1] then
    return -1
end
if redis.call('HGET', KEYS[1], 'cancelled') == '1' then
    return 1
end
local validUntil = tonumber(redis.call('HGET', KEYS[1], 'valid_until'))
if not validUntil then
    return 0
end
if tonumber(ARGV[3]) > validUntil then
    return 2
end
redis.call('HSET', KEYS[1],
        'cancel_request_id', ARGV[2],
        'cancelled_at', ARGV[3],
        'cancelled', '1')
return 1
