# ---- Build Stage ----
FROM eclipse-temurin:21-jdk-alpine@sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76 AS builder
WORKDIR /app

# Gradle wrapper
COPY gradlew gradlew
COPY gradle gradle
RUN chmod +x gradlew

# 루트 빌드 파일 (의존성 캐시 레이어)
COPY build.gradle.kts settings.gradle.kts gradle.properties gradle.lockfile ./
COPY build-logic build-logic

# 서브모듈 빌드 파일
COPY apps/api/build.gradle.kts apps/api/gradle.lockfile apps/api/
COPY apps/batch/build.gradle.kts apps/batch/gradle.lockfile apps/batch/
COPY architecture-tests/build.gradle.kts architecture-tests/gradle.lockfile architecture-tests/
COPY domain/build.gradle.kts domain/gradle.lockfile domain/
COPY infrastructure/common/build.gradle.kts infrastructure/common/gradle.lockfile infrastructure/common/
COPY infrastructure/api/build.gradle.kts infrastructure/api/gradle.lockfile infrastructure/api/
COPY infrastructure/batch/build.gradle.kts infrastructure/batch/gradle.lockfile infrastructure/batch/
COPY modules/jpa/build.gradle.kts modules/jpa/gradle.lockfile modules/jpa/
COPY modules/redis/build.gradle.kts modules/redis/gradle.lockfile modules/redis/
COPY supports/email/build.gradle.kts supports/email/gradle.lockfile supports/email/
COPY supports/logging/build.gradle.kts supports/logging/gradle.lockfile supports/logging/
COPY supports/monitoring/build.gradle.kts supports/monitoring/gradle.lockfile supports/monitoring/

# 의존성 다운로드 (소스 변경 없이 캐시 활용)
RUN ./gradlew :apps:batch:dependencies --dependency-verification strict --no-daemon --quiet

# 전체 소스 복사 후 빌드
COPY . .
RUN ./gradlew :apps:batch:bootJar --dependency-verification strict --no-daemon -x test

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring && mkdir -p /app/logs && chown spring:spring /app/logs
USER spring

COPY --from=builder /app/apps/batch/build/libs/app.jar /app/app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
