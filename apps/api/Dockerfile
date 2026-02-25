# ---- Build Stage ----
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Gradle wrapper
COPY gradlew gradlew
COPY gradle gradle
RUN chmod +x gradlew

# 루트 빌드 파일 (의존성 캐시 레이어)
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# 서브모듈 빌드 파일
COPY apps/api/build.gradle.kts apps/api/
COPY apps/batch/build.gradle.kts apps/batch/
COPY modules/jpa/build.gradle.kts modules/jpa/
COPY modules/redis/build.gradle.kts modules/redis/
COPY supports/logging/build.gradle.kts supports/logging/
COPY supports/monitoring/build.gradle.kts supports/monitoring/

# 의존성 다운로드 (소스 변경 없이 캐시 활용)
RUN ./gradlew :apps:api:dependencies --no-daemon --quiet || true

# 전체 소스 복사 후 빌드
COPY . .
RUN ./gradlew :apps:api:bootJar --no-daemon -x test

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring && mkdir -p /app/logs && chown spring:spring /app/logs
USER spring

COPY --from=builder /app/apps/api/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
