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

**And restore as the superuser or as `offline_owner` — never as the application's login role.**
Since PostgreSQL 15, `PUBLIC` has no `CREATE` on schema `public`, and the whole point of the
split-role setup above is that the application's role cannot create anything. Restore as it and
every `CREATE TABLE` is refused with `permission denied for schema public at character 14`,
followed by hundreds of `relation "public.x" does not exist` for the statements behind them —
the cause scrolls off the top and only the consequences are read. If a non-superuser restore is
unavoidable, the simplest fix is to make the role **own the database** — since PostgreSQL 15,
`public` is owned by `pg_database_owner`, whose only member is implicitly the database's owner,
so owning the database is owning the schema:

```bash
docker exec logsheet-db psql -U postgres \
    -c "ALTER DATABASE offline_first_db OWNER TO offline_first_app;"
```

(Or `CREATE DATABASE offline_first_db OWNER offline_first_app` if it does not exist yet. The
entrypoint creates `POSTGRES_DB` owned by `POSTGRES_USER`, which is exactly why the application
role cannot create in `public` by default.)

If the role must create in `public` but must **not** own the database, grant it instead:

```sql
GRANT CONNECT ON DATABASE offline_first_db TO offline_first_app;
\c offline_first_db
GRANT USAGE, CREATE ON SCHEMA public TO offline_first_app;
```

**`\c` is not decoration:** `GRANT … ON SCHEMA public` applies to the database you are connected
to, so running it without switching grants rights on the wrong `public` and reports success.

That is enough when the restore strips ownership and ACLs (`pg_restore --no-owner --no-acl`). If
the dump replays its own `GRANT`/`ALTER … OWNER` statements — the tell is
`WARNING: no privileges were granted for "public"` — only an owner may run those, so transfer the
schema instead:

```bash
docker exec logsheet-db psql -U postgres -d offline_first_db \
    -c "ALTER SCHEMA public OWNER TO offline_first_app;"
```

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
