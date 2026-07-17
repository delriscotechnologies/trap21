FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace
COPY src/main/java ./src/main/java
RUN find src/main/java -name '*.java' -print | sort > main-sources.txt \
    && mkdir -p build/main \
    && javac --release 21 -encoding UTF-8 -Xlint:all -d build/main @main-sources.txt \
    && jar --create --file /tmp/trap21.jar \
        --main-class com.delrisco.trap21.Trap21Application \
        -C build/main .

FROM build AS test
COPY src/test/java ./src/test/java
RUN find src/test/java -name '*.java' -print | sort > test-sources.txt \
    && mkdir -p build/test \
    && javac --release 21 -encoding UTF-8 -Xlint:all -cp build/main -d build/test @test-sources.txt \
    && java -ea -cp build/main:build/test com.delrisco.trap21.Trap21IntegrationTest

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S trap21 && adduser -S -G trap21 trap21 \
    && mkdir -p /app/data \
    && chown -R trap21:trap21 /app
WORKDIR /app
COPY --from=build /tmp/trap21.jar /app/trap21.jar
USER trap21

ENV TRAP21_BIND=0.0.0.0 \
    TRAP21_PORT=2121 \
    TRAP21_PASSIVE_START=30000 \
    TRAP21_PASSIVE_END=30009 \
    TRAP21_DATA_DIR=/app/data \
    TRAP21_COMMAND_TIMEOUT=15 \
    TRAP21_MAX_QUARANTINE_BYTES=268435456 \
    TRAP21_MAX_QUARANTINE_FILES=4096 \
    TRAP21_RETENTION_DAYS=30 \
    TRAP21_MAX_EVENT_LOG_BYTES=33554432 \
    TRAP21_MAX_EVENT_ARCHIVES=5 \
    TRAP21_MAX_SESSIONS_PER_IP=8

EXPOSE 2121 30000-30009
ENTRYPOINT ["java", "-jar", "/app/trap21.jar"]
