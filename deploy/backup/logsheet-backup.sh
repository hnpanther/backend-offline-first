#!/usr/bin/env bash
#
# Nightly backup for the containerised deployment — the database half and the attachments half.
#
# This is the Docker variant of the script in docs/deployment.md § "Backups — Linux". The order,
# the format and the pairing rule are identical; what changes is that pg_dump runs INSIDE the
# container and the attachments live in a named volume rather than on the host.
#
# Install:   /usr/local/bin/logsheet-backup.sh   (chmod 755, owned root:root)
# Schedule:  logsheet-backup.service + .timer in this directory
# Procedure: docs/deployment.md § "Backups — Docker on Linux"
#
# -e stops on error, -u on an unset variable, pipefail on a failure mid-pipe. Without these a
# failed step is skipped in silence and the job still reports success — which is the only kind of
# backup failure that matters, because nobody finds out until the restore.
set -euo pipefail

# ---------------------------------------------------------------------------------------------
# Configuration
#
# In the three-host topology the two halves are on two different machines. Install this script on
# both and turn off the half that is not there — the other host's copy takes it. On a single-host
# deployment leave both at 1.
# ---------------------------------------------------------------------------------------------
DO_DB=${DO_DB:-1}                 # the database host
DO_FILES=${DO_FILES:-1}           # the application host

DEST=${DEST:-/backup/logsheet}    # must be a local disk, and NOT the one the data is on
KEEP_DAYS=${KEEP_DAYS:-30}

# Database half ------------------------------------------------------------------------------
DB_COMPOSE=${DB_COMPOSE:-/opt/logsheet/deploy/postgres/docker-compose.yml}
DB_SERVICE=${DB_SERVICE:-db}
DB_NAME=${DB_NAME:-offline_first_db}
# The superuser over the container's LOCAL SOCKET, which the image's generated pg_hba.conf
# trusts — so no password appears anywhere: not in this file, not in `docker inspect`, not in the
# host's process list. If you harden pg_hba.conf, mount a .pgpass into the container and keep
# --no-password so the job fails loudly instead of waiting at a prompt.
DB_USER=${DB_USER:-postgres}
# The host side of the container's /backups mount. The dump is written there and verified there,
# because only the container's own pg_restore is guaranteed to match its server's version.
DB_BACKUP_DIR=${DB_BACKUP_DIR:-/opt/logsheet/deploy/postgres/backups}

# Attachments half ---------------------------------------------------------------------------
# The compose project name prefixes every volume: `name: logsheet-app` in the application's
# compose file plus the volume key `attachments` gives `logsheet-app_attachments`.
#   docker volume ls
ATTACH_VOLUME=${ATTACH_VOLUME:-logsheet-app_attachments}

# ---------------------------------------------------------------------------------------------

# Date and time to the second. Everything this run produces goes in here and nowhere else, so a
# run can neither disturb nor be confused with any other.
stamp=$(date +%F_%H%M%S)
RUN="$DEST/$stamp"

# The newest completed run, used as the hardlink source below. Empty on the first ever run, which
# then simply copies everything.
PREV=$(find "$DEST" -mindepth 1 -maxdepth 1 -type d -name '20*' 2>/dev/null | sort | tail -n 1)

mkdir -p "$RUN"

# Everything this run prints also lands in its own folder.
exec > >(tee -a "$RUN/backup.log") 2>&1
echo "run $stamp — previous: ${PREV:-none} — db=$DO_DB files=$DO_FILES"

# ---------------------------------------------------------------------------------------------
# 1) Database first.
#
# Why first: a reading row that points at an attachment which is not in the backup is a broken
# link you can see and investigate. An attachment with no row is an orphan nobody ever finds.
# Dumping the database first makes the file half a superset, which is the recoverable direction.
# ---------------------------------------------------------------------------------------------
if [ "$DO_DB" = 1 ]; then
    # --format=custom, never plain SQL: it is the only format pg_restore can filter, reorder and
    # parallelise, and the only one that survives being handled by anything other than psql.
    docker compose -f "$DB_COMPOSE" exec -T "$DB_SERVICE" \
        pg_dump --username="$DB_USER" --dbname="$DB_NAME" --no-password \
                --format=custom --compress=6 --file="/backups/$stamp.dump"

    # Prove the dump is readable NOW, not on the day it is needed. Cheap, and the only thing that
    # catches a zero-byte or truncated file. Run inside the container: its pg_restore always
    # matches its server, and a host client one major behind refuses outright.
    docker compose -f "$DB_COMPOSE" exec -T "$DB_SERVICE" \
        pg_restore --list "/backups/$stamp.dump" > /dev/null

    # Out of the shared directory and into this run's folder, so the next run cannot overwrite it
    # and nothing else can mistake it for a seed.
    mv "$DB_BACKUP_DIR/$stamp.dump" "$RUN/db.dump"
    chown root:root "$RUN/db.dump"
    chmod 600 "$RUN/db.dump"
    echo "db.dump $(stat -c %s "$RUN/db.dump") bytes"
fi

# ---------------------------------------------------------------------------------------------
# 2) Attachments second.
#
# A named volume is a directory on this host; ask Docker where rather than hardcoding
# /var/lib/docker/..., which is correct today and wrong the first time somebody changes the
# storage driver or the data-root.
#
# --link-dest makes an unchanged file a hardlink to the previous run's copy instead of a second
# copy of the bytes: every run folder is a complete tree, at the cost of the day's difference. It
# needs $RUN and $PREV on the same filesystem, which is why both are under $DEST.
# ---------------------------------------------------------------------------------------------
if [ "$DO_FILES" = 1 ]; then
    SRC=$(docker volume inspect -f '{{ .Mountpoint }}' "$ATTACH_VOLUME")
    [ -d "$SRC" ] || { echo "FATAL: volume $ATTACH_VOLUME has no mountpoint at $SRC"; exit 1; }

    if [ -n "$PREV" ] && [ -d "$PREV/attachments" ]; then
        rsync -a --delete --link-dest="$PREV/attachments" "$SRC/" "$RUN/attachments/"
    else
        rsync -a --delete "$SRC/" "$RUN/attachments/"
    fi
    echo "attachments $(du -sh "$RUN/attachments" | cut -f1) (apparent)"
fi

# ---------------------------------------------------------------------------------------------
# 3) Rotation — whole run folders.
#
# rm removes this run's links; the bytes survive as long as any remaining run still links to
# them, which is what makes the chain safe to prune from either end.
# ---------------------------------------------------------------------------------------------
find "$DEST" -mindepth 1 -maxdepth 1 -type d -name '20*' -mtime +"$KEEP_DAYS" -exec rm -rf {} +

echo "BACKUP OK $stamp — $RUN"
