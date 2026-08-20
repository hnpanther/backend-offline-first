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

```bash
# /etc/cron.daily/logsheet-backup
#!/bin/sh
set -e
d=$(date +%F)
pg_dump -U postgres offline_first_db | gzip > /backup/db-$d.sql.gz
tar czf /backup/attachments-$d.tar.gz -C /opt/logsheet/data attachments
find /backup -name '*.gz' -mtime +30 -delete
```

```powershell
# Scheduled task, daily. Same rule: both halves, same run.
$d = Get-Date -Format yyyy-MM-dd
& "C:\Program Files\PostgreSQL\18\bin\pg_dump.exe" -U postgres offline_first_db |
  Out-File "D:\Backup\db-$d.sql" -Encoding utf8
Compress-Archive -Path "D:\MyApp\logsheet\data\attachments" -DestinationPath "D:\Backup\attachments-$d.zip" -Force
```

A backup that captures only the database restores a system where every photo and voice note is a
broken reference. Verified restores matter more than frequent ones.

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
