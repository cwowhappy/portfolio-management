# 构建阶段
FROM gradle:9.7.0-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle gradle.properties ./
COPY src ./src
RUN gradle bootJar --no-daemon --console=plain

# 运行阶段
FROM eclipse-temurin:21-jre
# 安装 curl 供 docker-compose healthcheck 使用（temurin jre 镜像默认无 curl）
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
