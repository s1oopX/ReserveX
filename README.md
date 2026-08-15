# ReserveX

湿地公园预约系统。Redis 预占 + 普通消息 + 补偿 + 对账的抢号链路,三数据源分库分表,单进程模块化单体。

设计基准全部在 [`docs/`](docs/README.md) —— **动工前必读那三件**,尤其 `08 §十` 的编码红线 48 条。

## 一键起全栈

```bash
cp .env.example .env
# 生成四把密钥,四个值必须互不相同(启动时会断言,相同则拒绝启动)
openssl rand -base64 32   # AES_KEY,必须恰好 32 字节
openssl rand -base64 48   # ID_HASH_PEPPER
openssl rand -base64 48   # QR_HMAC_KEY
openssl rand -base64 48   # JWT_SECRET
# 填进 .env,然后:
docker compose up --build
```

访问 <http://localhost:8080>。超管账号 `admin@reservex.local`,初始密码取 `.env` 的
`ADMIN_INIT_PASSWORD`,**首次登录强制改密**。

> 邮箱是写死的(`02-seed.sql` 与 `application.yml` 各一处),不是环境变量 ——
> 它同时是 `email_route` 的主键,改它要两处一起改,否则登录第 1 跳查不到。

> ⚠️ `.env` 已在 `.gitignore` 里,**永远不要提交它**。仓库里只有 `.env.example`(变量名,无值)。

## 阶段 0 验收:必须在干净环境跑一次

```bash
docker compose down -v && docker compose up --build
```

`down -v` 不可省 —— MySQL 的 `docker-entrypoint-initdb.d` **只在数据目录为空时执行**,
所以开发者自己的机器永远不是干净的,建表脚本的错误在本机永远暴露不出来。

这一次里要验完四条地基(00 §6.5):

| # | 验什么 | 怎么验 |
|---|---|---|
| ① | 起得来 | 首页不是 404;三个库表齐;超管能登录且那行落在 `ds1` |
| ② | 密钥断言真会拦 | 把 `.env` 里两个变量填成同一个值,启动必须失败 |
| ③ | 三处时区一致 | MySQL / JVM / Jackson 都是 `Asia/Shanghai`(08 §7.2) |
| ④ | Redis 前提成立 | `CONFIG GET maxmemory-policy` = `noeviction`;`appendfsync` = `everysec` |

③④ 不验,所有时间判据和"库存放 Redis"的前提就建在错的地基上 ——
配成 `allkeys-lru` 时 Redis 会**静默删掉你的库存**,而现象是"莫名超卖"。

## 目录

| 路径 | 内容 |
|---|---|
| `docker-compose.yml` | 8 个服务:mysql / redis / rmqnamesrv / rmqbroker / rmq-init / frontend-build / backend / caddy |
| `docker/mysql/` | `01-schema.sql`(建三库)、`sharded-tables.sql`(分片表,被 include 两次)、`02-seed.sql`(超管 + 4 个场次模板) |
| `docker/rocketmq/topics.sh` | 显式建 5 个 topic 并设队列数 —— **队列数是消费并发的上限** |
| `caddy/Caddyfile` | 统一入口:静态资源 + `/api` 反代 + 屏蔽 `/actuator` |
| `backend/` | Spring Boot 3.3 / Java 21,单 jar 含 api + 消费者 + 定时任务 |
| `frontend/` | React 18 + Vite + Tailwind,产物由 `frontend-build` 拷进 `frontend-dist` 卷 |
| `stress-test/` | wrk2 抢号脚本(需先备 token 池) |

## 本地开发

```bash
# 后端(需要 mysql/redis/rocketmq 已起)
cd backend && mvn spring-boot:run

# 前端(vite dev server 代理 /api 到 localhost:8080)
cd frontend && npm install && npm run dev
```

后端环境变量走 shell 或 IDE 配置,不要在 `application.yml` 里写死任何密钥。

## 当前状态

核心抢号链路完整:注册登录、场次生成与放号、Redis 抢号(限流折叠进 Lua)、
MQ 异步落库、我的预约与取消、动态 QR / 手工核销、库存对账、管理端只读监控。

**P0+P1+P2 缺陷修复与补全(2026-08)** 已落地并验证(后端 21 测试全过、前端类型检查通过):

- **P0 正确性**:`IdCardRoute` 改 `INSERT` 抛 `DuplicateKeyException` 触发配额冲突回滚;
  新增 `OrphanRouteCleaner` 定时清理注册跨库两写失败留的孤儿 route(按分片键查、10min 守卫)。
- **P1 契约缺口**:补 7 个后端端点打通前端占位页 —— 增容、对账处置、放号监控、
  staff today、admin staff、DLQ 诚实化、全园预约查询 + 今日核销统计。
- **D9 workerId 动态化**:env `WORKER_ID` → Redis `INCR` mod 32 → fallback 1,多实例不撞 ID。
- **D7 批量过期扫描**:`ExpiryScanner` 广播两库 `RESERVED→EXPIRED`(now 用 `TimeSupport` 不用 SQL `NOW()`)。
- **D6 邮件提醒**:`ReminderWorker` 在 `valid_until` 前 30min 窗口发邮件,Redis `SET NX` 幂等,
  SMTP 密码是 placeholder 时 `catch MailAuthenticationException` 降级 `log.warn`。
- **D8 多类对账**:`reconcile-a`(Redis→DB)/ `reconcile-b`(反向)/ `route`(`id_card_route` vs reservation)/
  `pending-idx`(pending ZSet vs occupy 脏索引安全删)。
- **D5 Redis 限流**:user/slot 限流折叠进 `grab.lua` 保持 2 round-trip,超限返回 `-2` → `RATE_LIMITED`。
- **D4 验证码风控**:`CaptchaService` 生成/一次性校验/风控计数,抢号失败累计达阈值置 `captcha-required`,
  被风控用户须带 `captchaToken`({uuid}:{input});正常用户零额外 round-trip。

完整验收边界见 [`docs/07-页面与闭环.md`](docs/07-页面与闭环.md)。

`package-lock.json` 需在首次 `npm install` 后提交 —— 缺它则每次镜像构建
按 `^` 范围重新解析版本,同一个 commit 在不同日期构建出不同前端。
