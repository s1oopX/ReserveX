# ============================================================================
# ReserveX backend 镜像(08 §四 build: ./backend)
#
# ⚠️ 本机/Docker Hub 网络不稳时,maven:3.9 镜像拉取会 EOF。
#    本 Dockerfile 的构建层只依赖宿主已打好的 jar —— `mvn package` 在宿主完成,
#    镜像里不再跑 Maven。正常运行路径(能用 maven 镜像)切回多阶段构建,
#    见 git 历史或 08 §四。两条路径产出等价的运行层。
# ============================================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# §7.2:容器默认 UTC。compose 也设了 TZ,这里再钉一次 —— 单独 docker run 时也对
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata curl \
 && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
 && echo "Asia/Shanghai" > /etc/timezone

# 不用 root 跑应用
RUN addgroup -S app && adduser -S -G app app
COPY target/reservex-backend.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# ⚠️ 用 exec 形式(非 shell 形式):PID 1 必须是 java 进程本身,
#    否则 SIGTERM 落在 sh 上,优雅停机与 stop_grace_period: 45s 全部失效
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
