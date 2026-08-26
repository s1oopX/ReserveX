# ReserveX

湿地公园预约系统。Redis 预占 + 普通消息 + 补偿 + 对账的抢号链路,三数据源分库分表,单进程模块化单体。

设计基准全部在 [`docs/`](docs/README.md) —— **动工前必读那三件**,尤其 `08 §十` 的编码红线 48 条。

## 一键起全栈

```bash
cp .env.example .env
```

`.env.example` 里有 **14 个变量没有默认值,必须全部填上**。只填四把密钥是不够的 ——
漏任何一个,`docker compose` 的**所有子命令**都会因插值失败而报错,而报错只提缺失的那一个
(例如只说 Grafana 口令),不会告诉你还漏着另外几个。

```bash
# ① 四把密钥:必须两两不等,启动时 SecretGuard 会断言,相同即拒绝启动
openssl rand -base64 32   # AES_KEY,必须恰好 32 字节
openssl rand -base64 48   # ID_HASH_PEPPER
openssl rand -base64 48   # QR_HMAC_KEY
openssl rand -base64 48   # JWT_SECRET

# ② 基础设施口令(任意强口令即可)
#    MYSQL_ROOT_PASSWORD / MYSQL_APP_PASSWORD / REDIS_PASSWORD
#    ROCKETMQ_APP_SECRET_KEY / ROCKETMQ_ADMIN_SECRET_KEY  ← 这两个必须互不相同
openssl rand -hex 32

# ③ 观测面板口令。⚠️ 缺它 compose 直接拒绝解析 ——
#    Grafana 自己在没设口令时不报错,而是静默回落到 admin/admin
openssl rand -base64 24   # GRAFANA_ADMIN_PASSWORD

# ④ 超管首登密码(团队可见,首次登录强制改密)
#    ADMIN_INIT_PASSWORD

# ⑤ 邮件三项。演示环境可先填占位值,代价是发信会 535 失败(容器仍能起来):
#    QQ_SMTP_PASSWORD=占位  SMTP_FROM=you@qq.com  ALERT_EMAIL_TO=you@qq.com
#    ⚠️ SMTP_FROM 必须与 QQ_SMTP_PASSWORD 是同一个 QQ 账号,否则 SMTP 认证 535
```

填完自检 —— 直接查"还有哪个是空的",不数总行数(可选的内存覆盖项填不填都行,
总数因人而异):

```bash
grep -nE "^[A-Z_0-9]+=$" .env     # 有输出 = 那几行还没填;无输出才算填完
docker compose config --quiet && echo "插值通过"
docker compose up --build
```

访问 <http://localhost:8080>。超管账号 `admin@reservex.local`,初始密码取 `.env` 的
`ADMIN_INIT_PASSWORD`,**首次登录强制改密**。

> 邮箱是写死的(`02-seed.sql` 与 `application.yml` 各一处),不是环境变量 ——
> 它同时是 `email_route` 的主键,改它要两处一起改,否则登录第 1 跳查不到。

> ⚠️ `.env` 已在 `.gitignore` 里,**永远不要提交它**。仓库里只有 `.env.example`(变量名,无值)。

## 阶段 0 验收

```bash
docker compose config --quiet
docker compose up --build
```

已有卷由一次性 `mysql-migrate` 服务按 checksum 执行幂等迁移。不要在日常验收中执行
`docker compose down -v`;它会删除 MySQL、Redis、RocketMQ 业务数据。只有明确创建了可丢弃的
临时 Compose project 时,才对那个 project 做空库初始化测试。

这一次里要验完四条地基(00 §6.5):

| # | 验什么 | 怎么验 |
|---|---|---|
| ① | 起得来 | 首页不是 404;三个库表齐;超管能登录且那行落在 `ds1` |
| ② | 密钥断言真会拦 | 把 `.env` 里两个变量填成同一个值,启动必须失败 |
| ③ | 三处时区一致 | MySQL / JVM / Jackson 都是 `Asia/Shanghai`(08 §7.2) |
| ④ | Redis 前提成立 | `CONFIG GET maxmemory-policy` = `noeviction`;`appendfsync` = `always` |

③④ 不验,所有时间判据和"库存放 Redis"的前提就建在错的地基上 ——
配成 `allkeys-lru` 时 Redis 会**静默删掉你的库存**,而现象是"莫名超卖"。

## 目录

| 路径 | 内容 |
|---|---|
| `docker-compose.yml` | 15 个服务:6 个常驻(mysql/redis/rmqnamesrv/rmqbroker/backend/caddy)+ 3 个一次性(`mysql-migrate`/`rmq-init`/`frontend-build`)+ 6 个可观测(prometheus/alertmanager/redis-exporter/loki/alloy/grafana) |
| `docker/mysql/` | 初始化 DDL/种子与 `migrations/` 已有卷迁移 |
| `docker/rocketmq/topics.sh` | 显式建 3 个业务 topic + 3 个 DLQ topic并设队列数 |
| `caddy/Caddyfile` | 统一入口:静态资源 + `/api` 反代 + 屏蔽 `/actuator` |
| `backend/` | Spring Boot 3.5 / Java 21,单 jar 含 API + 消费者 + 定时任务 |
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
MQ 异步落库、我的预约与取消、动态 QR / 手工核销、库存对账、管理端运维处置。

**P0+P1+P2 缺陷修复与补全(2026-08)** 已落地并验证(后端 clean test、前端 lint/build、Docker 运行态 HTTP 合同):

- **严格资源式 API**:注册、会话、验证码、预约、核销、场次、模板、卡单、死信均使用资源 URI;
  JSON-only,404/405/406/413/415/501 统一错误包,写响应使用 201/202/204/409/412/428/429/503 等语义状态码。
- **RocketMQ 真实鉴权**:5.5 使用 `authenticationEnabled/authorizationEnabled` 管线；错误凭据被拒，
  应用账号仅可访问声明的业务 Topic/消费组，管理账号负责建 Topic；旧 `aclEnable` 假开启口径已清理。
- **注册幂等与恢复**:`Idempotency-Key` + durable outbox;完成返回 201,已受理返回 202,
  `GET /api/registrations/{registrationKey}` 查询 `PENDING/READY/STUCK`;管理员可通过
  `GET/PATCH /api/admin/registration-jobs/{userId}` 查询并重试 STUCK 任务。
- **卡单可见与逐桶对账**:用户列表/详情可见 `REVIEW_REQUIRED/FAILED`;库存对账逐桶比较,
  不再被“总数相等、桶位错配”绕过。

- **P0 正确性**:`IdCardRoute` 改 `INSERT` 抛 `DuplicateKeyException` 触发配额冲突回滚;
  新增 `OrphanRouteCleaner` 定时清理注册跨库两写失败留的孤儿 route(按分片键查、10min 守卫)。
- **P1 契约缺口**:补 7 个后端端点打通前端占位页 —— 增容、对账处置、放号监控、
  staff today、admin staff、DLQ 诚实化、全园预约查询 + 今日核销统计。
- **D9 workerId 边界**:prod 必须显式配置 `WORKER_ID=0..31`;非 prod 才允许自动/fallback。
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
