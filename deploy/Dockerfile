# Backend Offline-First — a container image for the plant.
#
# Build it from a jar that `mvn -o package` already produced, rather than building inside Docker:
# this project's deployment rule is "build on a machine with network access, deploy the artefact"
# (docs/deployment.md), and a Maven build inside the image would need a repository mirror on a
# host that deliberately has no internet route.
#
# Both commands run from the REPOSITORY ROOT, not from this directory: the build context is the
# root, because that is where target/ and .dockerignore are.
#
#   mvn -o clean package
#   docker build -f deploy/Dockerfile -t logsheet-backend:1.0.0 --build-arg APP_VERSION=1.0.0 .
#
# or, letting compose do the second half with the same Dockerfile:
#
#   docker compose -f deploy/app/docker-compose.yml build
#
# The image is layered on purpose — dependencies change rarely and the application constantly —
# so a rebuild after a code change ships one small layer instead of a 93 MB jar.

# The base image, once, for both stages. Pinned to the exact JRE build and Ubuntu release rather
# than `25-jre-noble`, which moves with every upstream rebuild: this line is the answer to "which
# Java and which OS are in production", and changing it is a decision with a commit, not a side
# effect of `docker build`. Ubuntu 26.04 (resolute) rather than 24.04 (noble): the same JRE, a
# newer package set, and standard support to 2031 instead of 2029.
#
# The digest as well as the tag: a tag can be pushed again, a digest cannot, so this line names
# one exact set of bytes. Docker resolves by the digest and ignores the tag when both are given,
# so the tag is there for the reader. It needs the image to have been PULLED on the build
# machine; a base that arrived by `docker load` carries no registry digest, and that build must
# pass the tag alone: --build-arg JRE_IMAGE=eclipse-temurin:25.0.5_8-jre-resolute
# Recorded 2026-11-16. When the base is moved to a newer build, change both halves together.
ARG JRE_IMAGE=eclipse-temurin:25.0.5_8-jre-resolute@sha256:480ef64e4540d45b0301e8afb7873bd3fa61ce93c5f66273eb569420718f38e2

# ---------------------------------------------------------------------------------------------
# Stage 1 — take the uber jar apart into its layers.
#
# The flags are the ones this Boot version actually accepts (4.1.1; verified against the jar).
# `--launcher` keeps Spring Boot's own JarLauncher, which is what the jar's manifest names.
# ---------------------------------------------------------------------------------------------
FROM ${JRE_IMAGE} AS layers

WORKDIR /build
COPY target/backend-offline-first-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------------------------
# Stage 2 — the image that runs.
# ---------------------------------------------------------------------------------------------
FROM ${JRE_IMAGE}

ARG APP_VERSION=0.0.1-SNAPSHOT

LABEL org.opencontainers.image.title="backend-offline-first" \
      org.opencontainers.image.description="Offline-first industrial log-sheet backend" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.licenses="GPL-3.0-or-later"

# `upgrade` first, because Ubuntu publishes security fixes faster than the base image is rebuilt,
# and an image scan lists every one of them as this image's. It cannot touch Java: Temurin is
# unpacked into /opt/java/openjdk, not installed as a package, so the JRE stays exactly the build
# the tag names.
#
# Then everything this application does not use is taken OUT. The base image is a general-purpose
# Java image; this is one Spring Boot process that opens sockets and files and starts nothing
# else (there is no ProcessBuilder or Runtime.exec anywhere in src/main). A package that is not
# installed cannot be vulnerable, and cannot be listed by a scan:
#
#   curl               used to be installed here for the HEALTHCHECK alone. The probe is now
#                      deploy/healthcheck.sh, which needs only bash. With curl go libcurl, GnuTLS,
#                      libldap, Cyrus SASL, MIT Kerberos, libssh2, nghttp2 — 23 packages. Java
#                      does its own TLS, LDAP and HTTP; none of them was ever loaded by the JVM.
#   p11-kit            there for the base image's own entrypoint (USE_SYSTEM_CA_CERTS), which this
#                      image replaces. The JRE trusts its own lib/security/cacerts.
#   fontconfig         fonts for AWT. Nothing here draws: the Excel exports set column widths by
#                      number and never call autoSizeColumn, which is the one POI path that asks
#                      for a font. Verified against a running container — every export, the import
#                      template and an import round trip (docs/deployment.md). IF A FUTURE EXPORT
#                      CALLS autoSizeColumn, OR ANYTHING STARTS RENDERING AN IMAGE, put this
#                      package back: without it that call fails at run time, not at build time.
#
# `--auto-remove` takes their now-orphaned dependencies with them. The checks at the end fail the
# BUILD if a later base image makes one of these impossible to remove, rather than letting an
# image ship that quietly still contains what this comment says is gone.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y \
    && DEBIAN_FRONTEND=noninteractive apt-get purge -y --auto-remove \
           curl p11-kit p11-kit-modules fontconfig \
    && rm -rf /var/lib/apt/lists/* \
    && for gone in curl libcurl4t64 libgnutls30t64 libp11-kit0 p11-kit libsasl2-2 libldap2 \
                   libexpat1 fontconfig libfontconfig1; do \
           if dpkg-query -W -f='${Status}' "$gone" 2>/dev/null | grep -q '^install ok installed'; then \
               echo "still installed: $gone" >&2; exit 1; \
           fi; \
       done

# Two things the base image ships outside the package manager, neither of which this image runs:
#
#   /usr/bin/pebble           Canonical's service manager, part of the Ubuntu base layer. A 10 MB
#                             Go binary; a scan reports its Go standard library against this
#                             image, and nothing here starts it (ENTRYPOINT below is `java`).
#   /__cacert_entrypoint.sh   the base image's entrypoint, replaced below. It calls `trust` from
#                             p11-kit, which is no longer installed.
RUN rm -f /usr/bin/pebble /__cacert_entrypoint.sh

# A numeric system uid with no login shell and no home. Numeric so that a Kubernetes
# `runAsNonRoot` check can verify it without resolving a name, and fixed so the volume
# permissions below keep working across image rebuilds.
RUN groupadd --system --gid 10001 logsheet \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin logsheet

# No setuid and no setgid file is left (CIS Docker Benchmark 4.8). The base image has eleven —
# su, mount, umount, passwd, chsh, chfn, gpasswd, chage, expiry, unix_chkpwd,
# pam_extrausers_chkpwd — and each is a way for a process that is not root to run something as
# root. This container already runs with no-new-privileges, which makes the bit inert; clearing
# it makes that true of the image too, however it is started. After the last package operation
# and after useradd, both of which may put one back.
RUN find / -xdev -perm /6000 -type f -exec chmod a-s {} + \
    && [ -z "$(find / -xdev -perm /6000 -type f)" ]

WORKDIR /app

# The four layers, coarsest first: everything above a changed layer is reused from cache.
COPY --from=layers --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=layers --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=10001:10001 /build/extracted/application/ ./

# The four paths this application writes to. Each one is a volume in production — see
# deploy/app/docker-compose.yml — and they are created here so that the container can run with a
# read-only root filesystem and still have somewhere to put a photograph.
#
# These exact strings are the contract with APP_ATTACHMENTS_STORAGE_DIR, APP_IMPORT_STORAGE_PATH,
# APP_TEMPLATE_GUIDES_STORAGE_DIR and APP_LOG_PATH in app.env. `attachments` is plural; a mount
# or a variable that says `attachment` points at a directory this line never created and never
# chowned, and on a bind mount that is a write the container cannot perform.
RUN mkdir -p /data/attachments /data/imports /data/template-guides /logs \
    && chown -R 10001:10001 /data /logs

# The health probe. `sed` strips a carriage return, because this repository is also checked out
# on Windows with core.autocrlf, and a script that begins `#!/bin/bash\r` does not run — the
# same guard as deploy/postgres/image/Dockerfile.
COPY --chmod=0755 deploy/healthcheck.sh /usr/local/bin/healthcheck
RUN sed -i 's/\r$//' /usr/local/bin/healthcheck

USER 10001:10001

EXPOSE 8081

# Readiness, not liveness, and not /api/health: only this one includes the database.
# Exec form, so no shell is started to run it: /bin/sh (dash) is never executed in this container.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD ["/usr/local/bin/healthcheck"]

# -XX:MaxRAMPercentage, not -Xmx: the container's memory limit is the number that matters, and
# hard-coding a heap means the limit and the heap drift apart the first time somebody changes
# one of them. 60% of the 12 GB limit in deploy/app/docker-compose.yml is ~7.2 GB — that file is
# sized for a dedicated 4 vCPU / 16 GB application host, and says why.
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
