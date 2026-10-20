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
