# syntax=docker/dockerfile:1

# ──────────────── build stage ────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 1) 빌드 파일 먼저 복사 → 의존성 레이어 캐싱 (소스만 바뀌면 재다운로드 안 함)
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies -q || true

# 2) 소스 복사 후 실행 가능한 boot jar 빌드 (테스트는 CI에서 별도 수행)
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# 3) plain jar 제외하고 boot jar만 선택 (java 플러그인이 -plain.jar도 만든다)
RUN cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -n1)" /workspace/app.jar

# ──────────────── runtime stage ────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

COPY --from=build /workspace/app.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
