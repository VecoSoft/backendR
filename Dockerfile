# Spring Boot API. Built on the droplet with deploy/build.sh, so the build is kept memory-light:
# Maven's heap is capped at 1 GB.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
ENV MAVEN_OPTS="-Xmx1g -XX:+UseSerialGC"
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# curl for the healthcheck only.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home app
COPY --from=build --chown=app:app /app/target/platform-0.1.0.jar app.jar
USER app
# Heap sized from the container's memory limit (mem_limit in docker-compose.prod.yml), not the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"
# application.yml's server.port reads ${PORT:8085}.
EXPOSE 8085
# Liveness only: a container whose DB is briefly unreachable should not be restarted for it.
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=4 \
    CMD curl -fsS http://localhost:8085/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
