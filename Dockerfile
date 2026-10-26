# 1단계: Maven으로 jar 빌드 (HomeFin과 같은 Maven 3.9 이미지)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 의존성 먼저 받기 (pom.xml이 그대로면 이 층은 캐시 사용)
COPY pom.xml .
RUN mvn -q dependency:go-offline

# 소스 복사 후 jar 빌드 (테스트는 CI에서 실행, 여기서는 생략)
COPY lombok.config ./
COPY src ./src
RUN mvn -q package -DskipTests

# 2단계: JRE로 jar 실행
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app
# 실행 전용 사용자 (root 아님)
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /app/target/*.jar app.jar
USER app
EXPOSE 8110
ENTRYPOINT ["java", "-jar", "app.jar"]
