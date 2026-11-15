#!/usr/bin/env bash
#
# Entrypoint of the derived PostgreSQL image (deploy/postgres/image/Dockerfile): one check, then
# the official docker-entrypoint.sh, unchanged.
#
# Why the check exists. From version 18 the official images keep the cluster in a subdirectory
# named after the major version — PGDATA=/var/lib/postgresql/18/docker — so that a later
# `pg_upgrade --link` can put 19/docker beside it on the same volume. The consequence: start a
# 19 image on this volume by mistake (a tag edited one digit too far) and ITS PGDATA, 19/docker,
# is empty. An empty PGDATA is exactly what the official entrypoint exists to initialise. It runs
# initdb and the initdb/ scripts and starts a new, EMPTY database beside the real one. What the
# application does next depends on how it logs in. As the superuser (shape 1 of the runbook) it
# logs in — postgres.env gives the new cluster the same password — Flyway builds the schema from
# V1, and the plant carries on writing into the wrong database while its data sits untouched one
# directory over. Nothing fails; that is the danger. With its own role (shapes 2 and 3) it fails
# to log in, or to find its schema, which is at least loud.
#
# So: when PGDATA holds no cluster but this volume already holds one from another version, stop
# before anything is created. A major upgrade is pg_upgrade, done on purpose —
# docs/deployment.md § "Major version upgrade". LOGSHEET_ALLOW_NEW_CLUSTER=1 lets one start
# through for the step of that procedure where a new cluster beside the old one is the intent.
#
# Only a server start is checked. `docker run <image> psql …` and `docker exec` are not affected.
set -Eeuo pipefail

first="${1:-}"
if [ "$first" = 'postgres' ] || [ "${first:0:1}" = '-' ]; then
    pgdata="${PGDATA:?PGDATA is not set}"
    pgdata="${pgdata%/}"
    if [ ! -s "$pgdata/PG_VERSION" ]; then
        shopt -s nullglob
        found=()
        # The 18+ layout, every major — and the pre-18 layout, a volume that held .../data.
        for marker in /var/lib/postgresql/*/docker/PG_VERSION /var/lib/postgresql/data/PG_VERSION; do
            dir="${marker%/PG_VERSION}"
            [ "$dir" = "$pgdata" ] && continue
            [ -s "$marker" ] && found+=("$dir   (PostgreSQL $(< "$marker"))")
        done
        if [ "${#found[@]}" -gt 0 ]; then
            if [ "${LOGSHEET_ALLOW_NEW_CLUSTER:-}" = 1 ]; then
                echo "WARNING: LOGSHEET_ALLOW_NEW_CLUSTER=1 — initialising a new cluster in $pgdata beside: ${found[*]}" >&2
            else
                {
                    echo "FATAL: refusing to create a new, empty PostgreSQL cluster in $pgdata."
                    echo "This volume already holds a cluster from another PostgreSQL version:"
                    printf '    %s\n' "${found[@]}"
                    echo "This image is PostgreSQL ${PG_MAJOR:-?}. Starting it here would create an EMPTY database"
                    echo "beside your data, and the application would connect to the empty one."
                    echo "  - Wrong image?  Put back the tag whose major version matches the data."
                    echo "  - Upgrading?    That is pg_upgrade, not a tag change: docs/deployment.md § Major version upgrade."
                    echo "Nothing has been written."
                } >&2
                exit 1
            fi
        fi
    fi
fi

exec docker-entrypoint.sh "$@"
