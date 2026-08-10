# 前端构建产物容器(08 §4.5)
#
# ⚠️ 这个镜像**不跑 nginx、不监听端口** —— 它只是个"产物搬运工":
#    构建出 dist/ 后由 compose 里的 frontend-build 一次性服务把内容拷进
#    frontend-dist 卷,Caddy 再从那个卷读。
#
# ⚠️ 为什么不做成 nginx 镜像?因为 08 §五 已定 Caddy 统一入口做
#    /api 反代 + try_files + 屏蔽 /actuator。再叠一层 nginx 等于两处配置
#    路由规则,改一处忘一处。
FROM node:20-alpine AS build
WORKDIR /app

# 先只拷清单:依赖没变时这层命中缓存,改一行业务代码不必重装 node_modules
COPY package*.json ./
# 有 lockfile 就 npm ci(严格复现),没有则退回 npm install。
#
# ⚠️ 骨架阶段仓库里还没有 package-lock.json,写死 `npm ci` 会让
#    `docker compose up --build` 在第一次就失败 —— 而这正是 09 §6.9 要抓的
#    "根本跑不到"那一档缺陷。
# ⚠️ 但**第一次本地构建后必须把 package-lock.json 提交进仓库**:
#    否则每次镜像构建都按 ^ 范围重新解析版本,同一个 commit 在不同日期
#    构建出不同前端,而问题现象会指向源码。
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

COPY . .
# 构建期做类型检查(package.json 的 build = tsc --noEmit && vite build):
# 类型错误必须让镜像构建失败,而不是产出一个能起但运行时炸的前端
RUN npm run build

# 最终镜像不需要 node 运行时,只需要一个能被 cp 的文件系统
FROM alpine:3.20
WORKDIR /app
COPY --from=build /app/dist ./dist
