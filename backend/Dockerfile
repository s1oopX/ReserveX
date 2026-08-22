FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# JVM 自带时区数据库；避免为了健康检查在每次构建时联网安装系统包。
ENV TZ=Asia/Shanghai

# 不用 root 跑应用
RUN addgroup -S app && adduser -S -G app app
COPY --from=build --chown=app:app /build/target/reservex-backend.jar app.jar
USER app

EXPOSE 8080

# ⚠️ 用 exec 形式(非 shell 形式):PID 1 必须是 java 进程本身,
#    否则 SIGTERM 落在 sh 上,优雅停机与 stop_grace_period: 45s 全部失效
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
