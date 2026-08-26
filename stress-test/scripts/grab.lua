-- ============================================================================
-- 抢号压测脚本(09 §一)。wrk2 用法:
--   wrk -t8 -c500 -d60s -R2000 -s scripts/grab.lua http://localhost:8080/api/reservations \
--       -- tokens.txt
--
-- ⚠️ 必须先准备 token 池:压测前用注册接口批量建号并登录,把 access token
--    一行一个写进 tokens.txt。**不要在压测里现登录** —— 那样测的是登录性能,
--    而抢号的瓶颈会被登录的 BCrypt(故意很慢)完全掩盖。
--
-- ⚠️ 一人一证一天一约(M6)意味着**每个 token 只能成功一次**。所以:
--    ① token 池规模必须 ≥ 压测期间的总请求数,否则后续请求全返 QUOTA_USED,
--       测出来的"QPS"其实是判重路径的 QPS,不是抢号路径的;
--    ② 每轮使用独立压测环境或新 Compose project 重置数据；严禁对业务环境执行 down -v。
--       否则第二轮全是 QUOTA_USED，这条是压测结果最容易被自己骗的地方。
--
-- wrk 的脚本环境模型(见 wrk/SCRIPTING 的 Overview):setup 与 done 共用一个
-- 环境,每个线程各有独立环境,两者**不连通**。因此:
--    · 跨环境传值只能靠 thread:set() / thread:get();
--    · 线程里要被 done() 读到的变量必须是**全局**(不能加 local)。
-- 这两条是本文件早先三个 bug 的共同根源,改动前请先读那一节。
-- ============================================================================

local slot_id = os.getenv("SLOT_ID") or ""

-- ── setup / done 环境 ──────────────────────────────────────────────────────
local threads = {}

function setup(thread)
   table.insert(threads, thread)
   thread:set("thread_id", #threads)
   -- 线程总数只有到最后一次 setup() 才确定,而 setup() 会在**任何线程开跑之前**
   -- 对每个线程各调一次(SCRIPTING:"all threads have been initialized but not
   -- yet started")。所以每次都向已登记的线程重播总数,收尾时人手一份正确值。
   -- n 是 -t 的值(个位数),O(n²) 无所谓。
   for _, t in ipairs(threads) do
      t:set("nthreads", #threads)
   end
end

-- ── 线程环境 ───────────────────────────────────────────────────────────────
local tokens = {}
local token_count = 0
local my_start = 0
local my_count = 0

function init(args)
   -- 下面三个必须是全局:done() 要跨环境 thread:get() 取回,local 取不到
   codes = {}
   sent = 0
   reused = 0

   local path = args[1] or "tokens.txt"
   local f = io.open(path, "r")
   if not f then
      error("找不到 token 池文件 " .. path .. " —— 见本文件头部说明,压测前必须先生成")
   end
   for line in f:lines() do
      line = string.gsub(line, "%s+$", "")
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

   -- 把 token 池切成连续区间,一线程一段,段间不重叠。
   -- 早先在 setup() 里按 token_count 取随机偏移,但 setup() 所在环境从没跑过
   -- init(),那里 token_count 恒为 0 —— 偏移恒为 0,所有线程齐步走同一批 token,
   -- 正是文件头 ② 要防的那种自骗。
   local base = math.floor(token_count / nthreads)
   local extra = token_count % nthreads
   my_count = base + (thread_id <= extra and 1 or 0)
   my_start = (thread_id - 1) * base + math.min(thread_id - 1, extra)
   if my_count == 0 then
      error(string.format(
         "token 池 %d 个,线程 %d 个,有线程分不到 token —— 减小 -t 或扩池",
         token_count, nthreads))
   end
end

function request()
   if sent >= my_count then
      -- 本线程区间用尽。回绕会让 QUOTA_USED 虚高,进而毁掉「OK 数 = capacity」
      -- 这条判据,所以计数并在 done() 里明说本轮不可用,而不是静默回绕。
      reused = reused + 1
   end
   local idx = my_start + (sent % my_count) + 1
   sent = sent + 1

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
function response(status, headers, body)
   local code = string.match(body, '"code"%s*:%s*"([A-Z_]+)"')
   if not code then
      codes["HTTP_" .. status] = (codes["HTTP_" .. status] or 0) + 1
      return
   end
   codes[code] = (codes[code] or 0) + 1
end

function done(summary, latency, requests)
   -- codes 累加在各线程自己的环境里,done() 在另一个环境 —— 必须逐线程取回再合并。
   -- 早先直接读本环境的 codes,那张表从头到尾是空的,业务码分布永远印不出来,
   -- 而 09 §三 的底线两条正是靠它断言的。
   local total = {}
   local total_reused = 0
   for _, thread in ipairs(threads) do
      for code, n in pairs(thread:get("codes")) do
         total[code] = (total[code] or 0) + n
      end
      total_reused = total_reused + thread:get("reused")
   end

   io.write("\n--- 业务码分布 ---\n")
   local names = {}
   for code in pairs(total) do
      table.insert(names, code)
   end
   table.sort(names)
   for _, code in ipairs(names) do
      io.write(string.format("%-24s %d\n", code, total[code]))
   end

   io.write("\n--- 关键判据(09 §三)---\n")
   io.write("OK 数应 = 该场次 capacity(超出即超卖,少于即库存泄漏)\n")
   io.write("SERVICE_DEGRADED 出现即说明 Redis 已到瓶颈,此时的 QPS 不算安全水位\n")
   io.write(string.format("p99 延迟: %.2fms\n", latency:percentile(99) / 1000))

   if total_reused > 0 then
      io.write(string.format(
         "\n⚠️ token 池被回绕复用 %d 次(池子小于总请求数)。这批 QUOTA_USED 里混了\n" ..
         "   复用造成的,「OK 数 = capacity」**不能用本轮结果断言** —— 扩池后重跑。\n",
         total_reused))
   end
end
