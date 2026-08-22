-- ============================================================================
-- 抢号压测脚本(09 §一)。wrk2 用法:
--   wrk -t8 -c500 -d60s -R2000 -s scripts/grab.lua http://localhost:8080/api/reservations
--
-- ⚠️ 必须先准备 token 池:压测前用注册接口批量建号并登录,把 access token
--    一行一个写进 tokens.txt。**不要在压测里现登录** —— 那样测的是登录性能,
--    而抢号的瓶颈会被登录的 BCrypt(故意很慢)完全掩盖。
--
-- ⚠️ 一人一证一天一约(M6)意味着**每个 token 只能成功一次**。所以:
--    ① token 池规模必须 ≥ 期望的成功预约数,否则后续请求全返 QUOTA_USED,
--       测出来的"QPS"其实是判重路径的 QPS,不是抢号路径的;
--    ② 每轮使用独立压测环境或新 Compose project 重置数据；严禁对业务环境执行 down -v。
--       否则第二轮全是 QUOTA_USED，这条是压测结果最容易被自己骗的地方。
-- ============================================================================

local tokens = {}
local token_count = 0
local slot_id = os.getenv("SLOT_ID") or ""

-- counter 每线程独立:wrk 的每个线程跑在独立 Lua VM 里,共享变量是做不到的。
-- 各线程从不同偏移取 token,避免多线程撞同一个 token(那会让 QUOTA_USED 虚高)
local counter = 0
local thread_offset = 0

function setup(thread)
   thread_offset = token_count > 0 and (math.random(token_count)) or 0
   thread:set("thread_offset", thread_offset)
end

function init(args)
   local path = args[1] or "tokens.txt"
   local f = io.open(path, "r")
   if not f then
      error("找不到 token 池文件 " .. path .. " —— 见本文件头部说明,压测前必须先生成")
   end
   for line in f:lines() do
      if #line > 0 then
         token_count = token_count + 1
         tokens[token_count] = line
      end
   end
   f:close()
   if token_count == 0 then
      error("token 池为空")
   end
   if slot_id == "" then
      error("未设置环境变量 SLOT_ID —— 必须指定压测哪个场次")
   end
end

function request()
   counter = counter + 1
   local idx = ((thread_offset + counter) % token_count) + 1

   wrk.method = "POST"
   wrk.headers["Accept"] = "application/json"
   wrk.headers["Content-Type"] = "application/json"
   local token = tokens[idx]
   wrk.headers["Authorization"] = string.match(token, "^Bearer%s+") and token
      or "Bearer " .. token
   -- slotId 用字符串:它是 Snowflake(19 位),JSON 里写成数字会被某些解析器
   -- 转成 double 而丢精度 —— 与 07 §3·补·4 前端那条是同一个坑
   wrk.body = string.format('{"slotId":"%s"}', slot_id)

   return wrk.format()
end

-- 按 JSON 业务码统计；HTTP 状态只作为非 JSON 响应的兜底分类。
local codes = {}

function response(status, headers, body)
   local code = string.match(body, '"code"%s*:%s*"([A-Z_]+)"')
   if not code then
      codes["HTTP_" .. status] = (codes["HTTP_" .. status] or 0) + 1
      return
   end
   codes[code] = (codes[code] or 0) + 1
end

function done(summary, latency, requests)
   io.write("\n--- 业务码分布 ---\n")
   for code, n in pairs(codes) do
      io.write(string.format("%-24s %d\n", code, n))
   end
   io.write("\n--- 关键判据(09 §三)---\n")
   io.write("OK 数应 = 该场次 capacity(超出即超卖,少于即库存泄漏)\n")
   io.write("SERVICE_DEGRADED 出现即说明 Redis 已到瓶颈,此时的 QPS 不算安全水位\n")
   io.write(string.format("p99 延迟: %.2fms\n", latency:percentile(99) / 1000))
end
