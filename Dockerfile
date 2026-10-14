# syntax=docker/dockerfile:1

# --- build: compiles and tests the application. The TWS API comes from the vendored Maven repository in /lib,
# so the build needs no manual step and no network access to Interactive Brokers.
FROM maven:3.10.0-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
COPY lib ./lib
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B package \
    && cp target/ibkrfacade-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# --- runtime: JRE only, non-root user
FROM eclipse-temurin:25.0.4.1_1-jre
RUN command -v curl >/dev/null 2>&1 || (apt-get update \
        && apt-get install -y --no-install-recommends curl \
        && rm -rf /var/lib/apt/lists/*) \
    && groupadd --system --gid 10001 ibkr \
    && useradd --system --uid 10001 --gid ibkr --no-create-home --shell /usr/sbin/nologin ibkr
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
COPY lib/TWS-API-LICENSE.txt /app/licenses/TWS-API-LICENSE.txt
USER 10001:10001

# Every setting comes from environment variables, see .env.example. No secret is stored in the image.
ENV SERVER_PORT=8082 \
    LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8082

# DOWN (unhealthy) while the TWS / IB Gateway or the Redis connection is broken
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS -o /dev/null "http://localhost:${SERVER_PORT}/ibkr/actuator/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
