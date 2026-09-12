FROM eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 AS build

WORKDIR /workspace
COPY src/main/java ./src/main/java
RUN find src/main/java -name '*.java' -print | sort > sources.txt \
    && mkdir -p build \
    && javac --release 21 -encoding UTF-8 -Xlint:all -d build @sources.txt \
    && jar --create --file /tmp/trap21.jar \
        --main-class com.delrisco.trap21.Trap21Application \
        -C build .

FROM eclipse-temurin:21-jre-noble@sha256:d35199d74a3b2dff1bfb435d9adde4ef974e74a7d2a4fcb3079063c55926edb5 AS runtime

RUN groupadd --system --gid 101 trap21 \
    && useradd --system --uid 100 --gid trap21 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin trap21 \
    && mkdir -p /app/data \
    && chown -R trap21:trap21 /app

WORKDIR /app
COPY --from=build /tmp/trap21.jar /app/trap21.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod 0555 /app/entrypoint.sh

USER trap21
ENV TRAP21_BIND=0.0.0.0 \
    TRAP21_DATA_DIR=/app/data

EXPOSE 2121 30000-30009
ENTRYPOINT ["/app/entrypoint.sh"]
