# syntax=docker/dockerfile:1
#
# OPTIONAL — a thin layer over the official PostgreSQL image, for when an audit requires it.
#
# PREFER THE OFFICIAL IMAGE. deploy/postgres/docker-compose.yml runs postgres:<tag> exactly as
# published, which is the image PostgreSQL's packagers and the Docker Official Images team test,
# patch and rebuild — and the one every PostgreSQL runbook assumes. Build this only when something
# you cannot otherwise satisfy demands it; in practice, a security review that the official image
# cannot pass as it stands:
#
#   CIS Docker Benchmark 4.1   the official image declares no USER (it starts as root and drops
#                              to postgres itself)
#   CIS Docker Benchmark 4.6   it declares no HEALTHCHECK (the compose file has one, but an image
#                              scanner only reads the image)
#   gosu                       a small Go binary built with an old Go runtime; scanners list ~60
#                              CVEs in it, although it only runs for an instant at start-up
#   Debian packages            security fixes Debian published after the image was built
#
# What it does NOT change is PostgreSQL. Every package from the PostgreSQL project's own
# repository is held at the version the tag names, so the server here is the official binary,
# byte for byte. docs/deployment.md § "The derived PostgreSQL image" has the measurements, the
# permissions it needs on the host, and when to stop using it.
#
# Build on the build machine — it needs the Debian and PostgreSQL mirrors — never on the plant
# host. The context is this directory and nothing else, so postgres.env and the backups beside it
# are never sent to the builder:
#
#   docker build -t logsheet-postgres:18.7-trixie deploy/postgres/image
#   docker save logsheet-postgres:18.7-trixie | gzip > logsheet-postgres-18.7-trixie.tar.gz
#
# then, on the database host, `docker load` it and put DB_IMAGE=logsheet-postgres:18.7-trixie in
# the .env beside docker-compose.yml.
#
# A MINOR upgrade (18.7 -> 18.8) is a rebuild, `--build-arg PG_TAG=18.8-trixie` and a new tag; the
# data volume is opened as it is. A MAJOR upgrade (18 -> 19) is not a rebuild — see
# logsheet-entrypoint.sh and docs/deployment.md § "Major version upgrade".

ARG PG_TAG=18.7-trixie
FROM postgres:${PG_TAG}

ARG PG_TAG
LABEL org.opencontainers.image.title="logsheet-postgres" \
      org.opencontainers.image.description="postgres:${PG_TAG} plus Debian security updates, a non-root USER and a HEALTHCHECK; the PostgreSQL server itself is unchanged" \
      org.opencontainers.image.base.name="docker.io/library/postgres:${PG_TAG}"

# 1. Debian security updates published since the official image was built — and nothing else.
#
# Every package whose version carries "pgdg" comes from the PostgreSQL project's repository — the
# server, its JIT, the client, libpq, postgresql-common — and is held first, so the server stays
# exactly the version the tag names. Without the hold, a rebuild after 18.8 reaches the mirror
# would put 18.8 binaries into an image called 18.7. `[ -n "$pgdg" ]` fails the build if the hold
# finds nothing to hold, rather than letting it upgrade everything in silence.
#
# --force-confold keeps the official image's own edit to postgresql-common's createcluster.conf
# (create_main_cluster = false) instead of stopping to ask about it.
RUN set -eux; \
    pgdg="$(dpkg-query -W -f '${Package} ${Version}\n' | awk '$2 ~ /pgdg/ { print $1 }')"; \
    [ -n "$pgdg" ]; \
    apt-mark hold $pgdg; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get upgrade -y -o Dpkg::Options::=--force-confold; \
    rm -rf /var/lib/apt/lists/*

# 2. gosu. docker-entrypoint.sh calls it in exactly one place — `if [ "$(id -u)" = '0' ]` — to drop
#    from root to postgres. This image starts as postgres (USER below), so that branch never runs.
#
# 3. The snakeoil key and certificate, and the hash link to the certificate. The ssl-cert package
#    generates them while the official image is built, so every copy of that image carries the
#    same key. Nothing here reads them: PostgreSQL runs with ssl = off, and its ssl_cert_file
#    default is server.crt, not this file.
#
#    Removing a file in a layer ABOVE does not take it out of the official layer BENEATH: the
#    image still carries that layer, and a scan of /var/lib/docker on the host reads every layer
#    on disk. What this does is keep them out of the running container's filesystem.
RUN set -eux; \
    rm -f /usr/local/bin/gosu \
          /etc/ssl/private/ssl-cert-snakeoil.key \
          /etc/ssl/certs/ssl-cert-snakeoil.pem; \
    find /etc/ssl/certs -lname 'ssl-cert-snakeoil.pem' -delete

# 4. The one check this image adds before the official entrypoint runs — see the script itself.
#    --chmod because a checkout on Windows carries no executable bit; the sed because an editor on
#    Windows can still save the file with CRLF, and bash then reports '$'\r': command not found'.
COPY --chmod=0755 logsheet-entrypoint.sh /usr/local/bin/logsheet-entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/logsheet-entrypoint.sh

# 5. Start as postgres, numerically: 999:999 is what the official image creates the user as (it
#    fixes the uid so that bind mounts can be prepared for it), and a number is what a Kubernetes
#    runAsNonRoot check can verify without resolving a name.
#
#    The one consequence for the host: the entrypoint can no longer chown anything. A named volume
#    is already right — Docker copies /var/lib/postgresql's ownership (999) into it, and a volume
#    the official image initialised is 999 too. A host directory must be handed over first:
#    `chown 999:999` for the data directory and for ./backups (docs/deployment.md).
USER 999:999

# 6. The same probe the compose file runs, so the image passes on its own. pg_isready asks whether
#    the server accepts connections; it does not log in, so no password is involved.
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=10 \
    CMD pg_isready -q -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-${POSTGRES_USER:-postgres}}" || exit 1

# A new ENTRYPOINT resets the CMD inherited from the base image to nothing, so CMD is restated.
# STOPSIGNAL (SIGINT — PostgreSQL's fast shutdown), the VOLUME, the exposed port and every ENV
# (PGDATA, PG_MAJOR, LANG, PATH) are inherited unchanged.
ENTRYPOINT ["logsheet-entrypoint.sh"]
CMD ["postgres"]
