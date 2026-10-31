# `deploy/` — everything needed to run this system in containers

Nothing in this directory is used by `mvn`, by the tests, or by a developer running the
application from an IDE. It is the deployment artefacts and only those, so that the repository
root stays the source tree.

The full procedure — in order, with every command, for Ubuntu — is
[docs/deployment.md § "Docker on Linux — the complete runbook"](../docs/deployment.md). This
file is the map of what is here and why.

## What is where

| Path | Goes on | What it is |
|---|---|---|
| `Dockerfile` | the **build** machine | Builds the application image from a jar `mvn -o package` already produced. Two stages; the first only takes the jar apart into layers |
| `../.dockerignore` | the **build** machine | Stays at the repository root, because the build context is the repository root. It narrows that context to exactly one file: the jar |
| `app/docker-compose.yml` | the **application** host | The application container, hardened, with its four volumes |
| `app/app.env.example` | the **application** host | Every environment variable the application reads, explained. Copy to `app.env`, `chmod 600` |
| `postgres/docker-compose.yml` | the **database** host | PostgreSQL 18.4, tuned, every setting a commented `-c` flag |
| `postgres/postgres.env.example` | the **database** host | Copy to `postgres.env`, `chmod 600` |
| `postgres/initdb/` | the **database** host | One-time cluster initialisation — see below |
| `backup/` | the **database** host | The nightly backup script and its systemd timer |

## Building the image

**From the repository root, not from this directory.** The `-f` says where the Dockerfile is;
the trailing `.` is the build context, and it has to be the root because that is where `target/`
and `.dockerignore` live.

```bash
mvn -o clean package && docker build -f deploy/Dockerfile -t logsheet-backend:1.0.0 --build-arg APP_VERSION=1.0.0 .
```

Two commands doing two unrelated things: the first runs the test suite and produces the jar and
knows nothing about Docker; the second copies that jar out of the context and assembles the
image, compiling nothing. In the wrong order it fails at
`COPY target/backend-offline-first-*.jar`, and that is the whole error.

`--build-arg APP_VERSION` only fills in the image's OCI `version` label, which is where
`docker inspect` reports it. **The tag is what actually selects the image** — tag every build and
never use `latest`, because the tag is the answer to "which version is in production" and it is
what makes a rollback two lines instead of a rebuild.

Letting compose drive the second half is identical, same Dockerfile:

```bash
mvn -o clean package && docker compose -f deploy/app/docker-compose.yml build
```

To a host with no registry and no internet route:

```bash
docker save logsheet-backend:1.0.0 | gzip > logsheet-backend-1.0.0.tar.gz
# on the plant host
docker load < logsheet-backend-1.0.0.tar.gz
```

## Two hosts, two projects

Two hosts, two compose projects, deliberately not one file. A database that shares a compose
project with the application is a database somebody restarts by accident — and `docker compose
down -v` in the wrong directory is the end of the plant's data.

## What `initdb/` actually is

`postgres/initdb` is bind-mounted into the database container at
`/docker-entrypoint-initdb.d`. That path is a convention of the official `postgres` image, not
of this project, and its rules are narrow enough to be worth stating exactly:

**It runs once.** The image's entrypoint looks at the data directory when the container starts.
If it is empty, it runs `initdb` to create a new cluster, starts a temporary server **on a local
socket only**, executes everything in `/docker-entrypoint-initdb.d`, shuts that server down, and
only then starts the real one. If the data directory already holds a cluster, the entrypoint
**skips the whole directory in silence** — no log line you would notice, no error. Scripts added
later never run.

**Filename order, three file types.** `*.sql` is piped to `psql`. `*.sql.gz` / `*.sql.xz` are
decompressed first. `*.sh` is executed if the file has the executable bit, and **sourced into
the entrypoint's own shell if it does not**. That difference matters more than it looks:

- A sourced script's `set -euo pipefail` stays in effect for the rest of the entrypoint, and
  `-u` in particular is not something that script was written to tolerate.
- A sourced script's `exit 1` aborts the entrypoint, not just the script.
- An executed script needs a real shebang on its **first** line. A blank line above `#!/bin/bash`
  makes it inert, and the kernel's `ENOEXEC` sends the file to `/bin/sh` instead — where
  `set -o pipefail` does not exist, so the script dies on its second line.

So: give every `.sh` here the executable bit and a first-line shebang, or neither and accept
that it is sourced. Do not mix.

**Only `psql` is available, and only locally.** During this phase the server is not listening on
TCP and `pg_hba.conf` is temporarily `trust` on the local socket. The scripts run as
`$POSTGRES_USER`, the superuser.

**What belongs in here:** creating roles, granting privileges, installing extensions — anything
that is a property of the cluster rather than of a release. This repository ships exactly one,
`10-app-role.sh.example`, which builds the README's split-role setup: a `NOLOGIN` schema owner
that migrations run as, and a DML-only user the application connects as, which cannot create or
drop anything even if something goes very wrong. Copy it to `10-app-role.sh` and fill in
`APP_DB_USER`/`APP_DB_PASSWORD` in `postgres.env` to use it; leave them empty and the simpler
single-role setup stands.

**What does not belong in here: restoring a dump.** It is tempting — the directory runs exactly
once, on a fresh cluster, which is exactly when you want to seed one. Three reasons not to:

1. **It only fires on an empty data directory.** After the first boot it is dead weight, and the
   one day it ever runs again is the day you rebuild the host after a failure — when it will
   restore whatever stale dump is still sitting in that directory instead of the backup you
   meant to use.
2. **A failure leaves a half-built cluster that looks finished.** If `pg_restore` exits
   non-zero, the entrypoint aborts, but the data directory it already created stays on disk. The
   next `docker compose up` sees a non-empty directory, skips `/docker-entrypoint-initdb.d`
   entirely, and starts a server with the roles but no schema. Flyway then migrates it from
   scratch and the application boots perfectly, against an empty database.
3. **It hides which dump is in production.** A restore is a decision somebody makes on a date,
   with a dump they chose. It should be a command in a runbook with its output on the screen,
   not a side effect of starting a container.

Restore explicitly instead — `docs/deployment.md § Restoring` has the command, and it is three
lines.

**And before restoring, create the roles the dump names.** A custom-format dump records an owner
per object and replays it as `ALTER ... OWNER TO`, so the restore lands ownership correctly
whoever runs it — but only if those roles exist. Missing, and you get one error per object:

```
ERROR:  role "offline_first_app" does not exist
Command was: ALTER FUNCTION public.asset_classes_enforce_single_level() OWNER TO offline_first_app;
```

Verified against a real backup of this system: restored with `--username=postgres`, **37 tables
owned by `offline_first_app`, 0 owned by anything else**. So the superuser is a fine choice; what
is not fine is adding `--no-owner` when the names already match, because ownership then falls to
the restoring role instead.

**`--role` is for the split-role setup with a `--no-owner` dump.** `offline_owner` is `NOLOGIN`,
so connect as the superuser and switch role for the duration:

```bash
docker exec -i logsheet-db pg_restore --role=offline_owner \
    --username=postgres --dbname=offline_first_db --no-owner --exit-on-error < db.dump

# the psql equivalent, for a plain .sql dump
docker exec -i -e PGOPTIONS='-c role=offline_owner' logsheet-db \
    psql -v ON_ERROR_STOP=1 -U postgres -d offline_first_db < dump.sql
```

Without it, a `--no-owner` restore leaves every table owned by `postgres`, and the grants this
script sets up do **not** cover them — `GRANT ... ON ALL TABLES` is a snapshot of an empty
database, and `ALTER DEFAULT PRIVILEGES FOR ROLE offline_owner` fires only for objects
`offline_owner` creates — so the application starts and answers
`permission denied for table users`. Measured on `postgres:18.4`.


Two more things that bite, both with misleading messages:

- **`relation "x" does not exist`** from the application, with the table plainly in the database:
  the role has no `USAGE` on the schema, so the table is invisible rather than forbidden.
- **`DROP SCHEMA public` run as the wrong role** re-owns the recreated schema to that role *and*
  destroys every `GRANT … ON SCHEMA public` with the old object. The next error is
  `no schema has been selected to create in`, which is about neither.

Full account, including the `ON_ERROR_STOP` a `.sql` dump needs because `--exit-on-error` is
`pg_restore`-only, in `docs/deployment.md § Restoring`.

## Bind mounts: the two stacks need opposite things

Both compose files ship **named volumes**, which inherit their ownership from the image and need
nothing. Swap one for a host directory and the two stacks stop behaving the same way:

| | `app/` | `postgres/` |
|---|---|---|
| Container starts as | **uid 10001 directly** (`USER 10001:10001`, `deploy/Dockerfile`) | **root**, then drops to `postgres` |
| Can fix ownership itself? | **No** | **Yes** — the entrypoint `chown`s the data directory first |
| So a bind mount needs | `chown` **before** the first start | nothing but an empty directory |

**The application's four paths** — `/data/attachments`, `/data/imports`, `/data/template-guides`,
`/logs`:

```bash
sudo mkdir -p /srv/logsheet/{attachments,imports,template-guides,logs}
sudo chown -R 10001:10001 /srv/logsheet
sudo chmod 750 /srv/logsheet/*
```

Miss the `chown` and there is no permission message — you get a Logback stack trace at start-up
or an attachment upload that fails with a server error, and both read like a code fault.

**The PostgreSQL data directory** — create it and stop:

```bash
sudo mkdir -p /srv/pgdata        # then mount it at /var/lib/postgresql, NOT at .../data
```

**Do not `chmod 777` it.** PostgreSQL refuses to start on a data directory that is group- or
world-accessible, and the message reads like a corrupt volume rather than a permissions mistake:

```
FATAL:  data directory "/var/lib/postgresql/18/docker" has invalid permissions
DETAIL:  Permissions should be u=rwx (0700) or u=rwx,g=rx (0750).
```

The entrypoint sets `0700` itself.

**`./backups`, though, is an ordinary directory and does follow the app's rule** — `pg_dump`
writes it from inside the container as `postgres` (uid 999 in these images; confirm with
`docker compose exec db id -u postgres`). `Permission denied` on `/backups/db.dump` is this:

```bash
sudo chown 999:999 backups
# or, since a dump drop-box has no permission rule of its own:
sudo chmod 777 backups
```

That is the one place in this directory where `777` is not a mistake — and it sits one line away
from the one place where it is fatal.

Under **user-namespace remapping** neither entrypoint can `chown` across the remap and both need
the host directory owned by the remapped uid. Decide that before the first start.

Full version, with the compose snippets: `docs/deployment.md § Host directories`.

## The variables compose reads about itself

Each compose file reads two kinds of variable and they do not mix:

- **`env_file:` entries** (`app.env`, `postgres.env`) go **into the container**. The application
  and PostgreSQL read them. These hold the secrets.
- **`${...}` substitutions inside the compose file** are read **by compose**, from the shell or
  from a `.env` file sitting beside that compose file. These are `APP_VERSION`, `APP_BIND_IP`,
  `APP_PORT` and `DB_BIND_IP`.

Putting `APP_BIND_IP` in `app.env` does nothing whatsoever, and the symptom is a stack that
publishes on its default — `127.0.0.1` — and is reachable from no other machine.

Both `.env` and `*.env` are already in `.gitignore`; the `*.example` files are not, which is why
they are the ones with the explanations in them.
