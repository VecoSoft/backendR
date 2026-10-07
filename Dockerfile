# Spring Boot API. Built on the droplet with `docker compose build`, so Maven's heap is capped
# at 1 GB to keep the build memory-light.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
ENV MAVEN_OPTS="-Xmx1g -XX:+UseSerialGC"
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --create-home app && mkdir -p /app/uploads && chown app /app/uploads
COPY --from=build /app/target/platform-0.1.0.jar app.jar
USER app
# Heap sized from the container's memory limit (see docker-compose.prod.yml), not the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
# application.yml's server.port reads ${PORT:8085}.
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
