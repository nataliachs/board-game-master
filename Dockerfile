# Container image for Cloud Run (or any container host).
# Build stage: compile the executable jar with the Gradle wrapper. Tests are not run here because
# the integration suite needs Docker; run `.\gradlew.bat check bootJar` locally before deploying.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar')" /workspace/app.jar

# Runtime stage: a small JRE image running as a non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
