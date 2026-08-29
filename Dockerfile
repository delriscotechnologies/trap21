FROM eclipse-temurin:21-jdk-alpine@sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76 AS build

WORKDIR /workspace
COPY src/main/java ./src/main/java
RUN find src/main/java -name '*.java' -print | sort > sources.txt \
    && mkdir -p build \
    && javac --release 21 -encoding UTF-8 -Xlint:all -d build @sources.txt \
    && jar --create --file /tmp/trap21.jar \
        --main-class com.delrisco.trap21.Trap21Application \
        -C build .

FROM eclipse-temurin:21-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64 AS runtime

RUN groupadd --system --gid 101 trap21 \
    && useradd --system --uid 100 --gid trap21 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin trap21 \
    && mkdir -p /app/data \
    && chown -R trap21:trap21 /app

WORKDIR /app
COPY --from=build /tmp/trap21.jar /app/trap21.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0555 /app/docker-entrypoint.sh

USER trap21
ENV TRAP21_BIND=0.0.0.0 \
    TRAP21_DATA_DIR=/app/data

EXPOSE 2121 30000-30009
ENTRYPOINT ["/app/docker-entrypoint.sh"]
