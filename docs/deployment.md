# Running the Backend as a Service — Windows and Linux

The application is a single executable JAR. Everything below is about making it start with the
machine, restart when it dies, and write its logs somewhere a person can find them — plus the
database and TLS it depends on.

The PWA half of the deployment (nginx, certificates, tablets) is in
[the PWA's `docs/deployment.md`](../../../FrontEnd/offline-first-pwa/docs/deployment.md). Read
both before the first install: the PWA's nginx is what terminates TLS and proxies `/api/` here,
so the two halves have to agree on host, port and origin.

---

## Before anything else

| | |
|---|---|
| Java | **25** (`<java.version>25</java.version>`). A JRE is enough; the JDK is only needed to build |
| PostgreSQL | any currently supported major. Verified on 18 |
| Artifact | `target/backend-offline-first-0.0.1-SNAPSHOT.jar`, built by `mvnw clean package`. Both deployments rename it to a stable name (`app.jar` on Linux, `backend-offline-first.jar` on Windows) so the service definition does not change every release |
| Listens on | `8081` by default (`SERVER_PORT`) |

**Build on a machine with network access, deploy the JAR.** The plant host does not need Maven,
a repository mirror, or an internet route — and should not have one.

```bash
./mvnw clean package
# target/backend-offline-first-0.0.1-SNAPSHOT.jar
```

### The settings that must not stay as they ship

Do this **before** the first start, not after. The application prints a bordered WARN block on
every boot naming each one still on its shipped value (`ProductionReadinessRunner`), and the full
list with reasoning is in the [README](../README.md#before-production--the-settings-that-must-not-stay-as-they-ship).
The short version:

| Variable | Why |
|---|---|
| `APP_AUTH_JWT_SECRET` | The shipped value is published in this repository. Anyone who can read it can forge a token for any user |
| `SPRING_DATASOURCE_PASSWORD` | Ships as `postgres` |
| `APP_CORS_ALLOWED_ORIGINS` | Ships as `*` |
| `APP_AUTH_LDAP_TRUST_SELF_SIGNED` | Ships as `true`, so the domain controller's certificate is not verified |

Generate a secret rather than inventing one:

```bash
# Linux
openssl rand -base64 48
```

```powershell
# Windows
[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Max 256 }))
```

### The directories the service owns

All four are relative to the **working directory** the service starts in, which is why every
service definition below sets it explicitly. A service that starts in `C:\Windows\System32` will
happily create `C:\Windows\System32\data\attachments` and nobody will find it again.

| Path | Holds | Back up? |
|---|---|---|
| `ProdLog/` | `app.log`, `business.log`, `audit.log`, `error.log` | No — rotated |
| `data/attachments/` | Captured photos, voice notes, video | **Yes, with the database** |
| `data/imports/` | Uploaded Excel files being processed | No |
| `data/template-guides/` | Groundwork; nothing writes it yet | No |

> **The attachments directory and the database must be backed up together.** The rows and the
> files are only meaningful as a pair: a restore of one without the other leaves readings pointing
> at bytes that are gone, or files nothing references.

**Size the disk for the attachments, not the database.** At a typical load — 10 sheets a day of
~50 assets — the database grows under 100 MB a year, while photos come to tens of GB and are
**never deleted by age**. The worked arithmetic is in
[README § What that box actually has to carry](../README.md#what-that-box-actually-has-to-carry).

---

# Linux — systemd

## 1. User, directories, permissions

A dedicated unprivileged account. The service never needs root: it binds 8081, not 443.

```bash
sudo useradd --system --home /opt/logsheet --shell /usr/sbin/nologin logsheet
sudo mkdir -p /opt/logsheet/{data,ProdLog}
sudo cp backend-offline-first-0.0.1-SNAPSHOT.jar /opt/logsheet/app.jar
sudo chown -R logsheet:logsheet /opt/logsheet
sudo chmod 750 /opt/logsheet
```

Copying the JAR to a stable name (`app.jar`) means the unit file does not change every release.

## 2. The environment file

Keep secrets out of the unit file: a unit is world-readable, an environment file does not have
to be.

```bash
sudo install -o root -g logsheet -m 640 /dev/null /etc/logsheet.env
sudo nano /etc/logsheet.env
```

```ini
# /etc/logsheet.env  — chmod 640, owned root:logsheet
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/offline_first_db
SPRING_DATASOURCE_USERNAME=logsheet
SPRING_DATASOURCE_PASSWORD=<a real password>

APP_AUTH_JWT_SECRET=<openssl rand -base64 48>
APP_CORS_ALLOWED_ORIGINS=https://192.168.1.4

APP_AUTH_LDAP_ENABLED=true
APP_AUTH_LDAP_URL=ldaps://dc.site.hnp:636
APP_AUTH_LDAP_DOMAIN=site.hnp
APP_AUTH_LDAP_TRUST_SELF_SIGNED=false

APP_LOG_PATH=/opt/logsheet/ProdLog
APP_ATTACHMENTS_STORAGE_DIR=/opt/logsheet/data/attachments
APP_IMPORT_STORAGE_PATH=/opt/logsheet/data/imports

SERVER_PORT=8081
```

`systemd` does **not** expand shell syntax in these files. `A=$B` is the literal string `$B`, and
quotes become part of the value. Write plain literals.

## 3. The unit

```ini
# /etc/systemd/system/logsheet.service
[Unit]
Description=Offline-first log sheet backend
# Wants=, not Requires=: PostgreSQL usually lives on this host, but if it is moved to another
# machine the unit must still start and wait rather than refuse. The readiness probe is what
# reports a database that is not there.
Wants=postgresql.service network-online.target
After=postgresql.service network-online.target

[Service]
Type=simple
User=logsheet
Group=logsheet
WorkingDirectory=/opt/logsheet
EnvironmentFile=/etc/logsheet.env

# -Xmx is the one JVM setting worth choosing deliberately; see README § Production sizing.
ExecStart=/usr/bin/java -Xms512m -Xmx2g -jar /opt/logsheet/app.jar

# Restart on any exit including a clean one — a clean exit here means something stopped the
# application, and this service has no reason to end on its own.
Restart=always
RestartSec=10s
# Do not give up after repeated fast failures: a database that is down for twenty minutes must
# not leave the application dead once it comes back.
StartLimitIntervalSec=0

# The JVM writes its own files under WorkingDirectory; nothing else on the host is ours.
ProtectSystem=full
ProtectHome=true
PrivateTmp=true
NoNewPrivileges=true
ReadWritePaths=/opt/logsheet

# Logback already writes rotated files under APP_LOG_PATH. Sending stdout to the journal as well
# would duplicate every line; keep only what the JVM prints before Logback starts.
StandardOutput=journal
StandardError=journal
SyslogIdentifier=logsheet

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now logsheet
sudo systemctl status logsheet
sudo journalctl -u logsheet -f
```

## 4. Confirm it is actually up

```bash
curl -s http://localhost:8081/actuator/health/readiness
```

**Use `readiness`, not `/api/health`.** Readiness includes the database. `/api/health` returns a
fixed `ok` and stays green with PostgreSQL down — it is a liveness-style check and a load balancer
pointed at it will keep sending traffic to a server that can serve none of it.

Then read the log for the readiness block:

```bash
grep -A6 "CONFIGURATION STILL ON ITS SHIPPED DEFAULTS" /opt/logsheet/ProdLog/app.log
```

Nothing there means nothing was left on a shipped default.

## 5. Upgrades

```bash
sudo systemctl stop logsheet
sudo cp backend-offline-first-0.0.1-SNAPSHOT.jar /opt/logsheet/app.jar
sudo chown logsheet:logsheet /opt/logsheet/app.jar
sudo systemctl start logsheet
```

**Take a database backup first.** Flyway applies any new migration on the next boot, and a
migration is not reversible by putting the old JAR back — the old code then meets a schema it does
not know. `ddl-auto=validate` means a mismatch is a refusal to start rather than silent damage,
which is the failure you want, but it is still a stopped plant.

```bash
pg_dump -U postgres offline_first_db | gzip > backup-$(date +%F).sql.gz
sudo tar czf attachments-$(date +%F).tar.gz -C /opt/logsheet/data attachments
```

---

# Windows — WinSW

Windows has no supervisor for a plain `java -jar`, and `sc.exe create` on `java.exe` does not
work: a Windows service must talk the Service Control Manager protocol and the JVM does not, so
the service registers and is then killed after 30 seconds for never reporting that it started.
Task Scheduler starts the process but nothing restarts it when it dies, and its state lives in a
different tool from every other service on the host.

**WinSW** wraps the process and registers it with the SCM properly. This deployment uses WinSW
**v3 in bundled mode**: the WinSW executable is renamed for the service, and an XML file with the
same base name sits beside it.

## 1. Layout

```text
D:\MyApp\backend-offline-first\
│
├── BackendOfflineFirst.exe      ← the renamed WinSW executable, NOT the application
├── BackendOfflineFirst.xml      ← the service definition; base name must match the .exe
├── backend-offline-first.jar    ← the Spring Boot JAR
│
├── logs\                        ← WinSW's own capture: .out.log / .err.log / .wrapper.log
├── ProdLog\                     ← the application's four Logback files
└── data\                        ← attachments, imports (created on first start)
```

> **`logs\` and `ProdLog\` are different things and both matter.** WinSW captures what the JVM
> prints on stdout/stderr — which, once Logback starts, is almost nothing. The real application
> log is the four files under `ProdLog\`. When something fails at startup the answer is in
> `logs\BackendOfflineFirst.wrapper.log`; when something fails at runtime it is in
> `ProdLog\app.log`. Looking in the wrong one is an hour lost.

Both `ProdLog\` and `data\` are created relative to `<workingdirectory>`, which is why that
element is not optional. Without it the service inherits the SCM's directory and quietly creates
`C:\Windows\System32\data\attachments`.

## 2. The service definition

`BackendOfflineFirst.xml`, beside `BackendOfflineFirst.exe`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<service>
    <id>BackendOfflineFirst</id>
    <name>Backend Offline First</name>
    <description>Backend Offline First - Spring Boot Application</description>

    <!-- An absolute path on purpose: a service must not depend on an interactive user's PATH.
         When the JDK is upgraded, this and JAVA_HOME below both have to change. -->
    <executable>C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe</executable>
    <arguments>-Xms512m -Xmx2g -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tehran -jar "D:\MyApp\backend-offline-first\backend-offline-first.jar" --spring.profiles.active=prod</arguments>
    <workingdirectory>D:\MyApp\backend-offline-first</workingdirectory>
    <env name="JAVA_HOME" value="C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"/>

    <startmode>Automatic</startmode>

    <logpath>D:\MyApp\backend-offline-first\logs</logpath>
    <log mode="roll-by-size">
        <!-- WinSW reads sizeThreshold in KB, so this is ~10 MB per file. -->
        <sizeThreshold>10240</sizeThreshold>
        <keepFiles>10</keepFiles>
    </log>

    <!-- Escalating backoff. A database that is down for twenty minutes must not become a
         restart storm, and must not leave the application dead once it comes back. -->
    <onfailure action="restart" delay="10 sec"/>
    <onfailure action="restart" delay="30 sec"/>
    <onfailure action="restart" delay="60 sec"/>
    <resetfailure>1 hour</resetfailure>

    <stoptimeout>30 sec</stoptimeout>
</service>
```

### Why each JVM argument is there

| Argument | Reason |
|---|---|
| `-Xms512m -Xmx2g` | The one sizing choice worth making deliberately — see [README § Production sizing](../README.md#production-sizing-jvm-heap-connection-pool-http-threads) |
| `-XX:+ExitOnOutOfMemoryError` | A JVM that has hit `OutOfMemoryError` is not reliably usable. Exiting turns an invisible sick process into a visible failure the service recovery can act on |
| `-Dfile.encoding=UTF-8` | Every user-visible string in this system is Persian. Excel exports, log files and import parsing all depend on it |
| `-Duser.timezone=Asia/Tehran` | The schedulers read their cron expressions in this zone, and Jalali formatting is done against it |
| `--spring.profiles.active=prod` | Selects `application-prod.properties` — see below |

### Where the secrets go

Two options, and the choice matters:

- **`application-prod.properties`** next to the JAR, selected by `--spring.profiles.active=prod`.
  This is what the arguments above assume. Keep the file **out of Git**.
- **`<env name="..." value="..."/>` entries in the XML**, one per variable, using the names in the
  [README's configuration table](../README.md#configuration-applicationproperties).

Either way the file ends up holding the database password and the JWT secret, so restrict it:

```powershell
icacls "D:\MyApp\backend-offline-first\BackendOfflineFirst.xml" /inheritance:r `
  /grant "Administrators:(R,W)" "SYSTEM:(R)"
icacls "D:\MyApp\backend-offline-first\application-prod.properties" /inheritance:r `
  /grant "Administrators:(R,W)" "SYSTEM:(R)"
```

The application prints a WARN block on every boot naming any setting still on its shipped value,
so a profile file that did not load announces itself rather than quietly running with the
published JWT secret.

## 3. Install and start

Run PowerShell **as Administrator**.

```powershell
cd "D:\MyApp\backend-offline-first"
.\BackendOfflineFirst.exe install
.\BackendOfflineFirst.exe start
.\BackendOfflineFirst.exe status
```

```powershell
Get-Service -Name BackendOfflineFirst
```

## 4. Commands

| Action | Command |
|---|---|
| Install | `.\BackendOfflineFirst.exe install` |
| Start | `.\BackendOfflineFirst.exe start` |
| Stop | `.\BackendOfflineFirst.exe stop` |
| Restart | `.\BackendOfflineFirst.exe restart` |
| Status | `.\BackendOfflineFirst.exe status` |
| Re-read the XML without reinstalling | `.\BackendOfflineFirst.exe refresh` |
| Uninstall | `.\BackendOfflineFirst.exe uninstall` |
| Process tree | `.\BackendOfflineFirst.exe dev ps` |
| Force-terminate a wedged wrapper | `.\BackendOfflineFirst.exe dev kill` |

`refresh` updates the registered service properties from the XML without an uninstall/install
cycle — but a change that affects the **running child process** (arguments, JVM flags, the JAR
path) still needs a restart to take effect.

`dev kill` is a troubleshooting fallback, never the normal stop. Stop first, uninstall second:
removing the executable or XML while the service is still registered leaves an entry Windows
cannot clean up.

## 5. Confirm it is actually up

```powershell
Invoke-WebRequest http://localhost:8081/actuator/health/readiness -UseBasicParsing |
  Select-Object -ExpandProperty Content
```

**Use `readiness`, not `/api/health`.** Readiness includes the database. `/api/health` returns a
fixed `ok` and stays green with PostgreSQL down — it is a liveness-style check, and a load
balancer pointed at it keeps sending traffic to a server that can serve none of it.

Then read the startup findings:

```powershell
Select-String -Path "D:\MyApp\backend-offline-first\ProdLog\app.log" `
  -Pattern "CONFIGURATION STILL ON ITS SHIPPED DEFAULTS" -Context 0,6
```

Nothing there means nothing was left on a shipped default.

## 6. Deploying a new JAR

```powershell
cd "D:\MyApp\backend-offline-first"

.\BackendOfflineFirst.exe status
.\BackendOfflineFirst.exe stop

# Back up the JAR you are replacing — this is what a rollback needs.
Copy-Item ".\backend-offline-first.jar" ".\backend-offline-first.jar.bak" -Force

# Copy the new build in as backend-offline-first.jar, then:
.\BackendOfflineFirst.exe start
.\BackendOfflineFirst.exe status
Get-Content ".\ProdLog\app.log" -Tail 100 -Wait
```

> **Take a database backup before this, not after.** Flyway applies any new migration on the next
> boot, and putting the old JAR back does not undo it — the old code then meets a schema it does
> not know. `ddl-auto=validate` turns that into a refusal to start rather than silent damage,
> which is the failure you want, but it is still a stopped plant.

## 7. Rollback

```powershell
.\BackendOfflineFirst.exe stop

# Keep the failed build rather than deleting it; you will want it to diagnose.
Move-Item ".\backend-offline-first.jar" ".\backend-offline-first.jar.failed" -Force
Move-Item ".\backend-offline-first.jar.bak" ".\backend-offline-first.jar" -Force

.\BackendOfflineFirst.exe start
.\BackendOfflineFirst.exe status
```

A rollback is only safe if the migrations have not moved. If they have, restore the database
backup taken before the upgrade, in the same operation.

## 8. When it will not start

Work through the logs in this order — each one answers a different question.

```powershell
cd "D:\MyApp\backend-offline-first"

# 1. Did WinSW manage to launch anything at all?
Get-Content ".\logs\BackendOfflineFirst.wrapper.log" -Tail 200

# 2. What did the JVM print before Logback took over?
Get-Content ".\logs\BackendOfflineFirst.err.log" -Tail 200
Get-Content ".\logs\BackendOfflineFirst.out.log" -Tail 200

# 3. What did the application itself say?
Get-Content ".\ProdLog\app.log" -Tail 200
Get-Content ".\ProdLog\error.log" -Tail 200
```

Then reproduce it outside the service, which separates "WinSW is misconfigured" from "the
application cannot start":

```powershell
& "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe" `
  -Xms512m -Xmx2g -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tehran `
  -jar "D:\MyApp\backend-offline-first\backend-offline-first.jar" --spring.profiles.active=prod
```

If that fails too, the problem is the application — database, credentials, port, profile — and
not the wrapper.

## 9. Service account

WinSW installs under a built-in account by default, which is more privilege than this needs. A
dedicated account is better; the only hard requirement is that it can write the application
directory.

```powershell
icacls "D:\MyApp\backend-offline-first" /grant "logsheet-svc:(OI)(CI)M"
```

Whatever account you choose must be able to: execute Java, read the JAR and the profile file,
write `logs\`, `ProdLog\` and `data\`, and reach PostgreSQL. If LDAPS is enabled against a domain
controller with a domain-issued certificate, a domain service account is usually simpler than
exporting the CA into the JVM truststore.

After changing the identity, test **start, stop, restart and a reboot** before calling it done —
a service that starts by hand and fails at boot is the usual result of a permissions change.

## 10. Windows firewall

Only if tablets reach 8081 directly. With nginx in front — the normal setup — **do not open 8081
at all**: nginx connects over loopback and 443 is the only port that needs a rule.

```powershell
# Only when there is no nginx in front.
New-NetFirewallRule -DisplayName "LogSheet API 8081" -Direction Inbound -LocalPort 8081 `
  -Protocol TCP -Action Allow -Profile Domain,Private
```

## 11. Both services together

The backend and nginx are separate Windows services, managed independently:

```powershell
Get-Service -Name BackendOfflineFirst,Nginx
```

```text
D:\MyApp\
├── backend-offline-first\
│   ├── BackendOfflineFirst.exe
│   ├── BackendOfflineFirst.xml
│   ├── backend-offline-first.jar
│   ├── logs\
│   ├── ProdLog\
│   └── data\
└── nginx\
    ├── NginxService.exe
    ├── NginxService.xml
    ├── nginx.exe
    ├── conf\
    ├── html\
    ├── ssl\
    └── logs\
```

The nginx half — including why its WinSW definition looks different from this one — is in
[the PWA's deployment guide](../../../FrontEnd/offline-first-pwa/docs/deployment.md).

---

## PostgreSQL as a service

Both installers register the database as a service themselves; there is nothing to write. What
matters is that it starts **before** the application, which the unit's `After=postgresql.service`
handles on Linux and Windows handles through the automatic-start ordering plus the application's
own restart-on-failure.

```bash
sudo systemctl enable --now postgresql
```

```powershell
Set-Service postgresql-x64-18 -StartupType Automatic
Start-Service postgresql-x64-18
```

Create the database and a dedicated role — the application must not connect as a superuser:

```sql
CREATE ROLE logsheet LOGIN PASSWORD 'a real password';
CREATE DATABASE offline_first_db OWNER logsheet;
```

Flyway creates the schema on first boot. Nothing else needs to be run by hand.

---

## Backups — one job, both halves

The data lives in **two places and neither is complete without the other**: the rows in
PostgreSQL, and the captured bytes under `data/attachments/`, date-sharded as
`2026/08/06/<uuid>.jpg`. The `attachments` table holds the *key*, never the file. So a backup of
the database alone restores a system in which every photo and voice note is a broken reference,
and a backup of the files alone is a pile of anonymous bytes.

**Both halves, one run, restored as the pair they were taken as.** Pairing Tuesday's database
with Wednesday's files produces exactly the broken references this rule exists to prevent.

### Order: database first, then files

The service stays up during a backup, so a tablet may upload an attachment between the two steps.
The order decides how that attachment ends up incomplete — and only one of the two answers is
survivable:

| Order | The attachment uploaded mid-backup | Result |
|---|---|---|
| **Database, then files** | Its row is not in the dump; its file is in the archive | **Orphan file — harmless** |
| Files, then database | Its row is in the dump; its file is not | **Broken reference — evidence lost** |

An orphan file costs nothing: the attachment sweep deletes any file no row references, after its
24-hour grace. A row whose file is missing cannot be repaired by anything.

**No downtime is needed.** `pg_dump` takes a consistent MVCC snapshot without locking the
application out, and attachment files are never modified after they are written — only added and
deleted — so copying the live directory is safe.

**Run at 01:00.** The attachment sweep runs at 02:00 and the audit retention purge at 03:00
([jobs.md](jobs.md)); backing up ahead of both keeps the three from contending, and means the
day's dump predates any sweep that acts on it.

### The dump format, and one command that silently corrupts it

Use **custom format** (`--format=custom`), not plain SQL: it is compressed, it allows selective
restore, and `pg_restore -j` can parallelise it — tens of minutes on a large database.

> **Never pipe a dump through PowerShell.**
> ```powershell
> pg_dump ... | Out-File -Encoding utf8   # WRONG — corrupts the dump
> ```
> A PowerShell pipe carries **text**, not bytes. The output is re-encoded, a BOM is prepended and
> line endings become CRLF. On a database full of Persian text the result is a plausible-looking
> file that fails on restore — and you find out on the day you need it. Pass
> `--file=<path>` and let `pg_dump` write the file itself. The same trap has a container form:
> see `docker exec -t` below.

### Authentication without an interactive password

**Nothing in the backup script carries the password, deliberately.** `pg_dump` gets it from
libpq, which looks in this order:

1. `PGPASSWORD` in the environment,
2. the file named by `PGPASSFILE`,
3. the default password file — `%APPDATA%\postgresql\pgpass.conf` on Windows, `~/.pgpass` on
   Linux,
4. failing all of those, it **prompts** — which under a scheduled task means the job fails
   instead of running.

Use option 3. `PGPASSWORD` in a script or a task definition is readable by anyone who can list
processes or export the task, and it ends up in transcripts.

```ini
# Windows: %APPDATA%\postgresql\pgpass.conf
# Linux:   ~/.pgpass   — chmod 600, or libpq ignores it silently
#
# hostname:port:database:username:password
127.0.0.1:5432:offline_first_db:logsheet_backup:REAL_PASSWORD
```

Three ways this goes wrong, all of which look like "the backup just fails at 01:00":

- **`%APPDATA%` is per user.** The task runs as its own service account, so the file has to be
  under *that* account — `C:\Users\svc-logsheet-backup\AppData\Roaming\postgresql\pgpass.conf`
  — not under the administrator who set it up. This is the usual cause. If placing a file in
  another account's profile is awkward, put it anywhere readable only by that account and point
  at it explicitly with `PGPASSFILE`.
- **The hostname must match what the command passes.** The script connects with
  `--host=127.0.0.1`, so the entry must begin `127.0.0.1`, not `localhost`. libpq compares the
  strings; it does not resolve them.
- **On Linux the mode must be 0600.** A group- or world-readable `~/.pgpass` is ignored without a
  word, and the job falls through to the prompt.

Add `--no-password` (`-w`) to `pg_dump` in a scheduled job. It makes libpq fail immediately
rather than trying to prompt, which turns a silent hang into an error the task records.

> **If the connection needs no password at all**, that is `pg_hba.conf` using `trust` — normal
> for a local socket and for the official Docker image, and the reason the container commands
> below carry no credentials. Check it before assuming the password file is what made the backup
> work; a `trust` line that is later tightened will break the job.

Give the job its own read-only role. It never needs to write, and a leaked read-only password
cannot change anything:

```sql
CREATE ROLE logsheet_backup LOGIN PASSWORD '…';
GRANT CONNECT ON DATABASE offline_first_db TO logsheet_backup;
GRANT USAGE ON SCHEMA public TO logsheet_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO logsheet_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO logsheet_backup;
```

### One folder per run, holding everything that run produced

Each run creates its **own timestamped folder** directly under the backup root and writes
everything into it — the log, the database dump and the attachments. Nothing is shared between
runs, so two runs can never touch the same file, a half-finished run cannot be mistaken for a
good one, and restoring means picking **one folder**:

```
/backup/logsheet/                     D:\Backup\logsheet\
├── 2026-09-11_085010/                one folder per run — this is the restore unit
│   ├── backup.log                    what this run did, and any error
│   ├── db.dump                       the database, custom format
│   └── attachments/                  the captured media, as of this run
├── 2026-09-10_085007/
└── 2026-09-09_085012/
```

The timestamp is `YYYY-MM-DD_HHMMSS`, so folders sort chronologically and two runs in the same
minute still get separate homes.

### What a per-run attachment copy costs, and how to avoid paying it

The attachment directory grows to tens of gigabytes and is **never pruned by age**. Copied in
full every night and kept for thirty days, it needs thirty times its own size on the backup disk.
Two ways out, and the answer differs by platform:

| | How | Cost of one run |
|---|---|---|
| **Linux** | `rsync --link-dest` against the previous run | Only the files that changed. Unchanged ones are **hardlinks** to the previous run's copy — the folder looks and restores like a full tree |
| **Windows** | Full copy (`robocopy /E`) | The whole tree, every run — so keep fewer runs, or use the hardlink variant below |

**Why hardlinks are safe here.** A snapshot built from hardlinks is only sound if nothing rewrites
a file *in place* — otherwise the edit would reach through the link and change every earlier
snapshot too. Attachment files are immutable by construction: they are written once and after
that only added or deleted, never modified. `rsync` also replaces rather than writes in place, so
it breaks the link even when a file does change. Both halves of that argument have to hold; if
either stops being true, drop to full copies.

**Sizing.** Work out `attachments size × retained runs` before choosing `KEEP_DAYS`. On Windows
with full copies, 30 GB of attachments and 30 days is 900 GB. Seven daily runs plus a weekly
archive is usually the better trade there; on Linux with `--link-dest`, thirty runs cost barely
more than one.

---

## Backups — Linux

### Before the first run

The script below carries **no credentials**. It will not work until these three exist — and each
one failing looks the same from the outside: the job runs at 01:00 and produces nothing.

| | What | Check it with |
|---|---|---|
| 1 | The read-only role exists (SQL above) | `psql -U postgres -c '\du logsheet_backup'` |
| 2 | `~/.pgpass` exists **for the user the unit runs as** (`User=logsheet`), mode `0600` | `sudo -u logsheet stat -c '%a %n' ~logsheet/.pgpass` |
| 3 | `/backup/logsheet` exists and that user can write to it | `sudo -u logsheet touch /backup/logsheet/.probe` |

Prove the password file is actually found, **as that user**, before trusting the schedule:

```bash
sudo -u logsheet psql --host=127.0.0.1 --username=logsheet_backup \
     --dbname=offline_first_db --no-password -c 'SELECT 1'
```

`fe_sendauth: no password supplied` means libpq did not find a matching entry — wrong user's home
directory, wrong mode, or a `localhost` entry where the command says `127.0.0.1`.

```bash
# /usr/local/bin/logsheet-backup.sh
#!/usr/bin/env bash
# -e stops on error, -u on an unset variable, pipefail on a failure mid-pipe.
# Without these a failed step is skipped in silence and the job still reports success.
set -euo pipefail

PGBIN=/usr/bin
APPDATA=/opt/logsheet/data
DEST=/backup/logsheet
KEEP_DAYS=30
DB_USER=logsheet_backup
DB_NAME=offline_first_db

# Date and time to the second. Everything this run produces goes in here and nowhere
# else, so a run can neither disturb nor be confused with any other.
stamp=$(date +%F_%H%M%S)
RUN="$DEST/$stamp"

# The newest completed run, used as the hardlink source below. Empty on the first ever
# run, which then simply copies everything.
PREV=$(find "$DEST" -mindepth 1 -maxdepth 1 -type d -name '20*' | sort | tail -n 1)

mkdir -p "$RUN"

# Everything this run prints also lands in its own folder.
exec > >(tee -a "$RUN/backup.log") 2>&1
echo "run $stamp — previous: ${PREV:-none}"

# 1) Database first — see the order table above.
dump="$RUN/db.dump"
# No password here: libpq reads ~/.pgpass for the user this unit runs as (chmod 600).
# --no-password makes a missing entry fail at once instead of prompting.
"$PGBIN/pg_dump" --host=127.0.0.1 --username="$DB_USER" --dbname="$DB_NAME" --no-password \
    --format=custom --compress=6 --file="$dump"

# 2) Attachments second, into this run's own folder.
#    --link-dest makes an unchanged file a hardlink to the previous run's copy instead of
#    a second copy of the bytes: the folder is a complete tree, at the cost of the day's
#    difference. It needs $RUN and $PREV on the same filesystem.
if [ -n "$PREV" ] && [ -d "$PREV/attachments" ]; then
    rsync -a --delete --link-dest="$PREV/attachments" \
        "$APPDATA/attachments/" "$RUN/attachments/"
else
    rsync -a --delete "$APPDATA/attachments/" "$RUN/attachments/"
fi

# 3) Prove the dump is readable now, not on the day it is needed.
#    Cheap, and the only thing that catches a zero-byte or truncated file.
"$PGBIN/pg_restore" --list "$dump" > /dev/null

# 4) Rotation — whole run folders. rm removes this run's links; the bytes survive as long
#    as any remaining run still links to them, which is what makes the chain safe to prune
#    from either end.
find "$DEST" -mindepth 1 -maxdepth 1 -type d -name '20*' -mtime +"$KEEP_DAYS" \
    -exec rm -rf {} +

echo "BACKUP OK $stamp — $RUN"
```

**A systemd timer, not cron.** The timer records the last run's status, sends output to the
journal, `Persistent=true` makes up a run the machine was off for, and `Type=oneshot` prevents
two copies overlapping. cron has none of that and mails its output to an address nobody reads.

```ini
# /etc/systemd/system/logsheet-backup.service
[Unit]
Description=LogSheet backup (database + attachments)
After=network-online.target postgresql.service

[Service]
Type=oneshot
User=logsheet
ExecStart=/usr/local/bin/logsheet-backup.sh
```

```ini
# /etc/systemd/system/logsheet-backup.timer
[Unit]
Description=Daily LogSheet backup at 01:00

[Timer]
OnCalendar=*-*-* 01:00:00
# Runs a missed backup once the machine is back. Without it, one night powered off is one
# day with no backup and nothing said about it.
Persistent=true
RandomizedDelaySec=5m

[Install]
WantedBy=timers.target
```

```bash
chmod +x /usr/local/bin/logsheet-backup.sh
systemctl daemon-reload
systemctl enable --now logsheet-backup.timer

# Test now rather than waiting for 01:00
systemctl start logsheet-backup.service
journalctl -u logsheet-backup.service -n 50
systemctl list-timers logsheet-backup.timer      # when it next fires
```

---

## Backups — Windows

### Before the first run

The script below carries **no credentials**. It will not work until these three exist — and each
one failing looks the same from the outside: the task runs at 01:00 and produces nothing.

| | What | Where |
|---|---|---|
| 1 | The read-only role exists (SQL above) | PostgreSQL |
| 2 | `pgpass.conf` exists **in the service account's own profile** | `C:\Users\svc-logsheet-backup\AppData\Roaming\postgresql\pgpass.conf` |
| 3 | The service account can read `D:\MyApp\logsheet\data` and write `D:\Backup\logsheet` | NTFS permissions |

Item 2 is the one that catches people: `%APPDATA%` resolves per user, so creating the file while
signed in as an administrator puts it in the *administrator's* profile, where the task will never
look. Either create it under the service account's profile directly, or place it anywhere only
that account can read and add `PGPASSFILE` to the task's environment.

Prove it works **as that account** rather than as yourself:

```powershell
# Signed in as the service account (runas, or an interactive session as that user):
& 'C:\Program Files\PostgreSQL\18\bin\psql.exe' `
    --host=127.0.0.1 --username=logsheet_backup --dbname=offline_first_db `
    --no-password -c 'SELECT 1'
```

`fe_sendauth: no password supplied` means libpq found no matching entry — wrong profile, or a
`localhost` entry where the command says `127.0.0.1`.

If you cannot sign in as the service account, register the task first and run it once with
`Start-ScheduledTask`; the transcript at `D:\Backup\logsheetuns\<stamp>ackup.log` names the
exact failure.

```powershell
# D:\MyApp\scripts\backup-logsheet.ps1
$ErrorActionPreference = 'Stop'

$PgBin    = 'C:\Program Files\PostgreSQL\18\bin'
$AppData  = 'D:\MyApp\logsheet\data'
$Dest     = 'D:\Backup\logsheet'
$KeepDays = 30
$DbUser   = 'logsheet_backup'
$DbName   = 'offline_first_db'

# Date and time to the second. Everything this run produces goes in here and nowhere
# else, so a run can neither disturb nor be confused with any other.
$stamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
$Run   = "$Dest\$stamp"
New-Item -ItemType Directory -Force -Path $Run | Out-Null
Start-Transcript -Path "$Run\backup.log" | Out-Null

try {
    # 1) Database first. --file, never a pipe: a PowerShell pipe re-encodes the dump.
    $dump = "$Run\db.dump"
    # No password here: libpq reads %APPDATA%\postgresql\pgpass.conf for the account this
    # task runs as. --no-password makes a missing entry fail at once instead of prompting.
    & "$PgBin\pg_dump.exe" `
        --host=127.0.0.1 --port=5432 --username=$DbUser --dbname=$DbName --no-password `
        --format=custom --compress=6 --file=$dump
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed with $LASTEXITCODE" }

    # 2) Attachments second, into this run's own folder — a full copy.
    #    /E copies the tree including empty directories. Not /MIR: the destination is new,
    #    so there is nothing to mirror away, and /MIR on a fresh folder only invites a
    #    typo in $Run to delete something else.
    #    /MT:8 uses eight threads, which roughly halves the wall time on a large tree.
    robocopy "$AppData\attachments" "$Run\attachments" `
        /E /R:2 /W:5 /MT:8 /NP /NDL /NFL /LOG:"$Run\robocopy.log"
    # robocopy reports 0-7 for success and 8+ for a real failure, so it cannot be
    # tested like an ordinary command. Reset the code or the next check inherits it.
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed with $LASTEXITCODE" }
    $global:LASTEXITCODE = 0

    # 3) Prove the dump is readable.
    & "$PgBin\pg_restore.exe" --list $dump | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "dump is unreadable: $dump" }

    # 4) Rotation — whole run folders.
    #    Age comes from the folder NAME, not its timestamp: a directory's LastWriteTime
    #    changes whenever anything inside it does. A name that does not parse is left
    #    alone, so nothing unexpected in this directory is ever deleted — including the
    #    run this script is writing right now.
    $cutoff = (Get-Date).AddDays(-$KeepDays)
    Get-ChildItem $Dest -Directory | Where-Object {
        $parsed = [datetime]::MinValue
        [datetime]::TryParseExact($_.Name, 'yyyy-MM-dd_HHmmss', $null,
            [Globalization.DateTimeStyles]::None, [ref]$parsed) -and $parsed -lt $cutoff
    } | Remove-Item -Recurse -Force

    Write-Output "BACKUP OK $stamp — $Run"
    Stop-Transcript | Out-Null
    exit 0
}
catch {
    Write-Output "BACKUP FAILED: $_"
    Stop-Transcript | Out-Null
    exit 1      # non-zero, so Task Scheduler records it as failed
}
```

> **This copies the whole attachment tree every run.** That is the price of a folder that
> restores on its own, and on Windows there is no `rsync --link-dest` to avoid it. Two ways to
> keep the disk in hand: lower `$KeepDays` (seven daily runs is usually enough when the off-site
> copy is weekly), or clone the previous run with hardlinks before the copy —
>
> ```powershell
> # Optional: costs almost no space, because only changed files become new bytes.
> # Safe only because attachment files are never rewritten in place — see the section above.
> $prev = Get-ChildItem $Dest -Directory | Where-Object { $_.Name -lt $stamp } |
>         Sort-Object Name | Select-Object -Last 1
> if ($prev -and (Test-Path "$($prev.FullName)\attachments")) {
>     $src = "$($prev.FullName)\attachments"
>     Get-ChildItem $src -Recurse -File | ForEach-Object {
>         $dst = Join-Path "$Run\attachments" $_.FullName.Substring($src.Length).TrimStart('\')
>         New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
>         New-Item -ItemType HardLink -Path $dst -Target $_.FullName | Out-Null
>     }
> }
> ```
>
> Then run the `robocopy` above with `/MIR` instead of `/E`, so files deleted since the previous
> run are removed from the clone. Both folders must be on the same NTFS volume.

Register the task **once**, from an elevated PowerShell:

```powershell
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "D:\MyApp\scripts\backup-logsheet.ps1"'

$trigger = New-ScheduledTaskTrigger -Daily -At 01:00

$principal = New-ScheduledTaskPrincipal `
    -UserId 'DOMAIN\svc-logsheet-backup' -LogonType Password -RunLevel Highest

$settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Hours 4) `
    -RestartCount 2 -RestartInterval (New-TimeSpan -Minutes 15) `
    -DontStopOnIdleEnd

Register-ScheduledTask -TaskName 'LogSheet Backup' `
    -Action $action -Trigger $trigger -Principal $principal -Settings $settings `
    -Description 'Daily pg_dump of offline_first_db plus a mirror of data\attachments.'
```

| Option | Why it is there |
|---|---|
| `-NoProfile -NonInteractive` | No user profile, and the script can never block on input — a forgotten prompt would otherwise hang the task forever |
| `-StartWhenAvailable` | Runs a missed backup once the server is back. Without it, one night powered off is one day with no backup |
| `-MultipleInstances IgnoreNew` | A backup still running must not have tomorrow's stacked on top of it |
| `-ExecutionTimeLimit` | A wedged run is killed after four hours rather than surviving until next week |
| `-RunLevel Highest` | Read access to the service's `data` directory |
| `-LogonType Password` | The task must run with nobody signed in; Windows asks for the account password at registration |

```powershell
# Test now, and check the result
Start-ScheduledTask   -TaskName 'LogSheet Backup'
Get-ScheduledTaskInfo -TaskName 'LogSheet Backup' |
    Select-Object LastRunTime, LastTaskResult, NextRunTime
# LastTaskResult 0 is success. Anything else: read the newest
# D:\Backup\logsheet\runs\<stamp>\backup.log
```

> **A job that fails every night looks exactly like one that succeeds every night** — until the
> day you need it. Add a second scheduled task that checks each morning that today's dump exists
> and is a plausible size, and alerts if not.

---

## Backups — PostgreSQL in a container

Only the *commands* change. The order, the format and the pairing rule are identical.

**These commands carry no password on purpose.** `docker exec` connects over the container's
local socket, and the official `postgres` image ships `local all all trust` in its generated
`pg_hba.conf` — so a local connection needs no credentials at all. That is a property of the
image, not a guarantee: if you harden `pg_hba.conf`, put a `.pgpass` inside the container (or
mount one) and keep `--no-password` so the job fails loudly instead of waiting for a prompt.
Passing `-e PGPASSWORD=…` to `docker exec` works but writes the password into
`docker inspect` output and the host process list — prefer the file.

```bash
CONTAINER=offline_first_db          # docker ps --format '{{.Names}}'
DEST=/backup/logsheet
stamp=$(date +%F_%H%M%S)
RUN="$DEST/$stamp"; mkdir -p "$RUN"

# Write the dump inside the container, then copy it out. This avoids every
# stream-mangling problem a pipe or a TTY can introduce.
docker exec "$CONTAINER" \
    pg_dump -U logsheet_backup -d offline_first_db \
            --format=custom --compress=6 --file=/tmp/db.dump
docker cp "$CONTAINER:/tmp/db.dump" "$RUN/db.dump"
docker exec "$CONTAINER" rm -f /tmp/db.dump

# Verify, in the container (its pg_restore always matches its server).
docker exec -i "$CONTAINER" pg_restore --list /dev/stdin < "$RUN/db.dump" > /dev/null
```

> **Never pass `-t` to `docker exec` when the output is a dump.** A TTY translates `\n` to
> `\r\n`, which corrupts a custom-format dump exactly as the PowerShell pipe does. If you stream
> rather than copy, use `-i` alone:
> ```bash
> docker exec -i "$CONTAINER" pg_dump -U logsheet_backup -d offline_first_db -Fc > db.dump
> ```

Two more container-specific rules:

- **Dump with the container's own `pg_dump`, not the host's.** A host client older than the
  server refuses to run (`server version mismatch`), and the container's binaries always match
  its server. This is a reason to prefer `docker exec` even when a host client exists.
- **Never back up the data volume by copying it while the server runs.** `PGDATA` copied live is
  torn and unusable. If you want a physical backup, use `pg_basebackup`; otherwise `pg_dump` is
  the whole story.

If the **application** is also containerised, the attachments live in a volume rather than on the
host, so mirror them through a throwaway container:

```bash
docker run --rm \
    -v logsheet_attachments:/data:ro \
    -v "$DEST/attachments":/mirror \
    alpine sh -c 'cp -a /data/. /mirror/'
```

On Windows with Docker Desktop, the same script works from PowerShell with `docker.exe`; keep the
`--file` / `docker cp` shape rather than piping, for the reason above.

---

## Retention, and the copy that is not on this machine

> **A backup on the same disk is not a backup.** A failed disk takes the data and the backup
> together, and ransomware encrypts the destination drive first.

| Cycle | Copies | Where |
|---|---|---|
| Daily run folders | 30 on Linux, 7 on Windows | Local backup disk |
| Weekly — copy one run folder | 12 | NAS or a second server |
| Monthly — copy one run folder | 12 | Off-site |

The database is small — under 100 MB a year at typical load — so keeping thirty daily dumps costs
almost nothing. The attachments are not, and the run folder holds a copy of them: that is what
`KEEP_DAYS` is really sizing. On Linux the hardlinks make thirty runs cost about as much as one;
on Windows each run is a full copy, so weekly and monthly copies are best taken by moving whole
run folders off this disk rather than by keeping more of them on it.

Back up the **environment file** too (`/etc/logsheet.env`, or the WinSW `<env>` block), separately
and encrypted. It holds the database password, the JWT secret and the LDAP settings; putting it in
the same archive that goes off-site means anyone who obtains that archive owns the live system.

---

## Restoring

Take the dump and the attachment archive **from the same run**. If you are restoring because
something broke, copy the current state aside first — a broken system sometimes holds data the
backup does not.

1. **Stop the service.** A running application keeps writing, and Flyway may apply a migration
   mid-restore.
   ```bash
   sudo systemctl stop logsheet          # Linux
   ```
   ```powershell
   Stop-Service BackendOfflineFirst      # Windows
   ```

2. **Restore the database.** Recreating it is the reliable way — a restore over a live schema
   leaves whatever the dump does not mention.
   ```bash
   dropdb   -U postgres offline_first_db
   createdb -U postgres -O logsheet offline_first_db
   pg_restore -U postgres -d offline_first_db -j 4 \
       /backup/logsheet/2026-09-11_085010/db.dump
   ```
   In a container, copy the dump in first and run `pg_restore` there:
   ```bash
   docker cp 2026-09-11_085010/db.dump offline_first_db:/tmp/restore.dump
   docker exec offline_first_db dropdb   -U postgres offline_first_db
   docker exec offline_first_db createdb -U postgres -O logsheet offline_first_db
   docker exec offline_first_db pg_restore -U postgres -d offline_first_db -j 4 /tmp/restore.dump
   ```

3. **Restore the attachments** to whatever `APP_ATTACHMENTS_STORAGE_DIR` points at.
   ```bash
   rsync -a --delete /backup/logsheet/2026-09-11_085010/attachments/ \
         /opt/logsheet/data/attachments/
   chown -R logsheet:logsheet /opt/logsheet/data/attachments
   ```
   ```powershell
   robocopy "D:\Backup\logsheet\2026-09-11_085010\attachments" `
            "D:\MyApp\logsheet\data\attachments" /MIR
   ```

4. **Start, and watch it come up.** `ddl-auto=validate` means a successful boot is itself proof
   that every entity matches the restored schema. `Migration checksum mismatch` here means the
   dump and the JAR are from different versions — restore the matching pair, do not edit
   `flyway_schema_history` (AGENTS.md gotcha #86).

5. **Verify** — next section. A restore nobody checked is a guess.

### Verifying a restore

```sql
SELECT 'log_sheets', count(*) FROM log_sheets
UNION ALL SELECT 'entries',     count(*) FROM log_sheet_entries
UNION ALL SELECT 'attachments', count(*) FROM attachments
UNION ALL SELECT 'users',       count(*) FROM users;

SELECT version, description, success
FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
```

Then check that every attachment row has its file. `storage_key` is the path relative to the
storage root, with forward slashes:

```sql
\copy (SELECT storage_key FROM attachments ORDER BY storage_key) TO '/tmp/keys.txt'
```

```bash
root=/opt/logsheet/data/attachments
missing=0
while IFS= read -r key; do
  [ -f "$root/$key" ] || { echo "MISSING $key"; missing=$((missing+1)); }
done < /tmp/keys.txt
echo "rows with no file: $missing"
```

```powershell
$root = 'D:\MyApp\logsheet\data\attachments'
$missing = Get-Content C:\temp\keys.txt |
    Where-Object { -not (Test-Path (Join-Path $root ($_ -replace '/', '\'))) }
"rows with no file: $($missing.Count)"
```

**Rows with no file must be zero.** Files with no row are fine — that is the orphan from the
order table, and the sweep collects it. Finally open one completed sheet with a photo in the
panel: it is the only check that exercises row, key, file, permissions and service together.

### The monthly drill

Most organisations that lose data had backups. What they did not have was evidence those backups
could be restored. Once a month, half an hour:

1. Take a run folder from **last week**, not the newest — this tests rotation too.
2. Restore it into a scratch database: `createdb restore_drill && pg_restore -d restore_drill -j 4 …`
3. Run the count queries above and compare them with that day's figures.
4. Spot-check a few `storage_key` values against the attachment archive of the same date.
5. `dropdb restore_drill`, and **write down the date, which backup, how long it took, and what
   was wrong**. That duration is your real RTO, and performing the drill is the only way to know
   it.

### If 24 hours of loss is too much

Daily backups mean an **RPO of about 24 hours**. For most sites that is acceptable, because the
tablets still hold their own copy of unsynced rounds and re-send them. If it is not, the next
tier is WAL archiving:

```ini
# postgresql.conf
wal_level = replica
archive_mode = on
archive_command = 'test ! -f /wal-archive/%f && cp %p /wal-archive/%f'
```

That buys point-in-time recovery and an RPO of minutes, at the cost of more storage, more
maintenance, and a restore procedure that itself has to be rehearsed.

> **WAL archiving does not cover the attachments.** Rewinding the database to 14:30 while the
> newest attachment mirror is from 01:00 leaves every photo uploaded in between as a broken
> reference. If you adopt PITR, mirror the attachments hourly as well — `rsync` only moves the
> difference, so it is cheap.

---

## When it will not start

| Symptom | Cause |
|---|---|
| `Port 8081 was already in use` | A previous instance is still running. On Windows this is the usual result of starting the JAR by hand and then starting the service |
| `Migration checksum mismatch` | The JAR's migrations disagree with `flyway_schema_history`. See [AGENTS.md](../AGENTS.md) gotcha #86 — never "fix" it by deleting rows blindly |
| `Schema-validation: missing table/column` | `ddl-auto=validate` doing its job: the JAR is older than the database, or a migration did not run |
| Service starts then stops immediately, no log | Almost always the working directory: it cannot create `ProdLog`. Check `AppDirectory` / `WorkingDirectory` |
| Readiness 503, `/api/health` 200 | The database is unreachable. That difference is the whole point of using readiness for the probe |
| Starts, but tablets get CORS errors | `APP_CORS_ALLOWED_ORIGINS` does not include the PWA's origin, scheme and port included |

---

## Related

- [PWA deployment — nginx, certificates, tablets](../../../FrontEnd/offline-first-pwa/docs/deployment.md)
- [README § Before production](../README.md#before-production--the-settings-that-must-not-stay-as-they-ship)
- [README § Production sizing](../README.md#production-sizing-jvm-heap-connection-pool-http-threads)
- [docs/jobs.md § Production readiness check](jobs.md#production-readiness-check)
