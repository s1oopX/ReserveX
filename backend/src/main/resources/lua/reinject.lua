-- Count the replay before publishing. Never recreate an already-cleaned occupy hash.
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
return redis.call('HINCRBY', KEYS[1], 'reinject_count', 1)
