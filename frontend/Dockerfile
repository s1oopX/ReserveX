# 前端构建产物容器(08 §4.5)
#
# ⚠️ 这个镜像**不跑 nginx、不监听端口** —— 它只是个"产物搬运工":
#    构建出 dist/ 后由 compose 里的 frontend-build 一次性服务把内容拷进
#    frontend-dist 卷,Caddy 再从那个卷读。
#
# ⚠️ 为什么不做成 nginx 镜像?因为 08 §五 已定 Caddy 统一入口做
#    /api 反代 + try_files + 屏蔽 /actuator。再叠一层 nginx 等于两处配置
#    路由规则,改一处忘一处。
FROM node:22-alpine AS build
WORKDIR /app

# 先只拷清单:依赖没变时这层命中缓存,改一行业务代码不必重装 node_modules
COPY package*.json ./
# lockfile 已纳入仓库；镜像构建必须严格复现它。
RUN npm ci

COPY . .
# 构建期做类型检查(package.json 的 build = tsc --noEmit && vite build):
# 类型错误必须让镜像构建失败,而不是产出一个能起但运行时炸的前端
RUN npm run build

# 最终镜像只负责把 dist 拷进命名卷；复用已拉取的 Alpine 基础镜像，保证
# compose 的 sh/cp/rm 命令有完整的动态加载器和运行库。
FROM node:22-alpine
WORKDIR /app
COPY --from=build /app/dist ./dist
