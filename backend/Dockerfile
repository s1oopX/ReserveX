# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# §7.2:容器默认 UTC。compose 也设了 TZ,这里再钉一次 —— 单独 docker run 时也对
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata curl \
 && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
 && echo "Asia/Shanghai" > /etc/timezone

# 不用 root 跑应用
RUN addgroup -S app && adduser -S -G app app
COPY --from=build /build/target/reservex-backend.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# ⚠️ 用 exec 形式(非 shell 形式):PID 1 必须是 java 进程本身,
#    否则 SIGTERM 落在 sh 上,优雅停机与 stop_grace_period: 45s 全部失效
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
