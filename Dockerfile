# Backend Offline-First — a container image for the plant.
#
# Build it from a jar that `mvn -o package` already produced, rather than building inside Docker:
# this project's deployment rule is "build on a machine with network access, deploy the artefact"
# (docs/deployment.md), and a Maven build inside the image would need a repository mirror on a
# host that deliberately has no internet route.
#
#   mvn -o clean package
#   docker build -t logsheet-backend:1.0.0 --build-arg APP_VERSION=1.0.0 .
#
# The image is layered on purpose — dependencies change rarely and the application constantly —
# so a rebuild after a code change ships one small layer instead of a 93 MB jar.

# ---------------------------------------------------------------------------------------------
# Stage 1 — take the uber jar apart into its layers.
#
# The flags are the ones this Boot version actually accepts (4.1.1; verified against the jar).
# `--launcher` keeps Spring Boot's own JarLauncher, which is what the jar's manifest names.
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-noble AS layers

WORKDIR /build
COPY target/backend-offline-first-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------------------------
# Stage 2 — the image that runs.
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-noble

ARG APP_VERSION=0.0.1-SNAPSHOT

LABEL org.opencontainers.image.title="backend-offline-first" \
      org.opencontainers.image.description="Offline-first industrial log-sheet backend" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.licenses="GPL-3.0-or-later"

# curl is here for one reason: the health probe below has to ask for /actuator/health/readiness,
# which includes the database. A TCP check on 8081 would come back healthy with PostgreSQL down,
# and that is the failure this project's deployment guide explicitly warns about.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# A numeric system uid with no login shell and no home. Numeric so that a Kubernetes
# `runAsNonRoot` check can verify it without resolving a name, and fixed so the volume
# permissions below keep working across image rebuilds.
RUN groupadd --system --gid 10001 logsheet \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin logsheet

WORKDIR /app

# The four layers, coarsest first: everything above a changed layer is reused from cache.
COPY --from=layers --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=layers --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=10001:10001 /build/extracted/application/ ./

# The four paths this application writes to. Each one is a volume in production — see
# docker-compose.yml — and they are created here so that the container can run with a read-only
# root filesystem and still have somewhere to put a photograph.
RUN mkdir -p /data/attachments /data/imports /data/template-guides /logs \
    && chown -R 10001:10001 /data /logs

USER 10001:10001

EXPOSE 8081

# Readiness, not liveness, and not /api/health: only this one includes the database.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8081/actuator/health/readiness || exit 1

# -XX:MaxRAMPercentage, not -Xmx: the container's memory limit is the number that matters, and
# hard-coding a heap means the limit and the heap drift apart the first time somebody changes
# one of them. 60% of a 6 GB limit is ~3.6 GB, which is what the README's sizing table asks for
# when PostgreSQL is on another host.
#
# -XX:+ExitOnOutOfMemoryError so the container dies and is restarted instead of limping on with
# a heap it cannot work in — a half-alive instance is worse than a restarted one, because the
# health probe can still pass while every request fails.
#
# No secrets, no datasource URL and no JWT key here: everything environment-specific arrives as
# an environment variable at run time.
ENTRYPOINT ["java", \
            "-XX:MaxRAMPercentage=60", \
            "-XX:+ExitOnOutOfMemoryError", \
            "-XX:+UseContainerSupport", \
            "-Djava.security.egd=file:/dev/urandom", \
            "org.springframework.boot.loader.launch.JarLauncher"]
