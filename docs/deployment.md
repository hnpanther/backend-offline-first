# Running the Backend as a Service — systemd, WinSW, or Docker

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

**Exactly one instance may run against one database.** Not a performance guideline — a
correctness one. Since 2026-10-17 the generation tick defends itself: it advances each template's
cursor with a compare-and-set, so a second instance's rounds roll back and it logs
`Template N was advanced by another writer during this tick` instead of leaving a duplicate round
in the operators' pool. **That is a safety net, not permission** — everything else a second
instance does is still unguarded, and two copies of every scheduled job is not a configuration
anybody should choose ([jobs.md](jobs.md#what-a-second-instance-does--its-work-is-thrown-away-and-it-says-so)
has the mechanism and the measurements). Three places the rule is kept:

- **Stop the service before replacing the JAR.** Both upgrade procedures below do; the point of
  the order is that the old JVM is gone before the new one starts, not merely that the file is
  free to overwrite.
- **Never leave a manual run pointing at the production database.** `mvnw spring-boot:run`
  defaults to `jdbc:postgresql://localhost:5432/offline_first_db`, which on the plant host is the
  production database. Kill the port-8081 process when you are done — on Windows,
  `Get-NetTCPConnection -LocalPort 8081` names it.
- **One service definition per host, one host per database.** If a second node is ever wanted —
  Kubernetes, or any N-replica deployment — [roadmap.md §19](roadmap.md) is the complete
  inventory: the import subsystem's restart recovery would kill another instance's running
  import and delete its file, three stores sit on local disk, six places hold per-JVM state, and
  a two-Deployment shape (N stateless pods plus one worker) removes most of it. Two of its steps
  are mandatory; the rest is configuration.

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
| `APP_AUTH_LDAP_TRUST_SELF_SIGNED` | Ships as `true`, so neither the domain controller's certificate nor its name is verified — pin the controllers' certificates with `APP_AUTH_LDAP_TRUSTSTORE` instead |

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
APP_AUTH_LDAP_URL=ldaps://dc01.site.hnp:636 ldaps://dc02.site.hnp:636
APP_AUTH_LDAP_DOMAIN=site.hnp
APP_AUTH_LDAP_TRUST_SELF_SIGNED=false
APP_AUTH_LDAP_TRUSTSTORE=/opt/logsheet/config/ad-truststore.p12
APP_AUTH_LDAP_TRUSTSTORE_PASSWORD=<the truststore's password>
APP_AUTH_LDAP_VERIFY_HOSTNAME=true

APP_LOG_PATH=/opt/logsheet/ProdLog
APP_ATTACHMENTS_STORAGE_DIR=/opt/logsheet/data/attachments
APP_IMPORT_STORAGE_PATH=/opt/logsheet/data/imports

SERVER_PORT=8081
```

Four things about the LDAP block specifically, each of which fails in a way that points somewhere else:

- **`APP_AUTH_LDAP_DOMAIN` is the UPN suffix, not the DC's hostname.** The bind is `username@<domain>`, so it has to be the suffix Active Directory itself accepts, which is frequently not the DNS name of the server. Wrong here, every user gets an ordinary *invalid credentials* error — it reads as a password problem, not a configuration one.
- **`ldap://` and `ldaps://` are not interchangeable.** `ldaps://` (636) is encrypted. `ldap://` (389) sends the bind, and with it the user's AD password, in clear text. Use it only for a DC that genuinely offers no TLS, and expect the readiness check to report it at every boot — for any `ldap://` entry in the list, not only the first.
- **`APP_AUTH_LDAP_TRUST_SELF_SIGNED` governs LDAPS only** and is inert on an `ldap://` URL. That is deliberate rather than an oversight: the trust-all socket factory used to be applied on any scheme, so a plaintext URL opened an *SSL* socket to port 389 and failed every bind with a TLS handshake error that named nothing relevant. `true` now also switches the hostname check off (the JDK enforces it even through a trust-everything factory, which is why the flag used to look inert against a bare IP). Set it `false` and pin the controllers' certificates instead — below.
- **A user must already exist in `users`.** The directory verifies the password; it does not create accounts. `users.auth_type` decides which backend may accept the account: `LOCAL`, `ACTIVE_DIRECTORY`, or `HYBRID` (local password first, then the directory). Give every administrator a HYBRID account with a real local password: **an outage of the directory never locks a HYBRID account out**, because the local password is checked before the directory is contacted at all.

### Active Directory behind a load balancer, with self-signed certificates

The usual production shape is not one controller with a public certificate. It is a domain name
(`site.hnp`) that DNS answers with any of several controllers, each with its own self-signed
certificate, and not all of them necessarily listening on 636. Configured naïvely as
`ldaps://site.hnp:636` that fails three ways at once: the name is not on any certificate, no
certificate is trusted, and a controller without 636 turns some logins into a wait for a TCP
timeout. The settings map onto that shape one to one — the same recipe as the file-management
application, so one procedure serves both.

**1. List the controllers, not the balancer.** JNDI takes a space- or comma-separated list and
tries each in order until one connects, so the application does its own fail-over:

```
APP_AUTH_LDAP_URL=ldaps://dc01.site.hnp:636 ldaps://dc02.site.hnp:636 ldaps://dc03.site.hnp:636
```

A controller that does not listen on 636 refuses the connection in milliseconds and the next is
tried; one that is down but not refusing is bounded by `APP_AUTH_LDAP_TIMEOUT_MS` (default 5 s)
instead of the operating system's twenty-odd seconds. That one number bounds the bind's answer
too — JNDI applies its connect timeout to the initial bind, so there is deliberately no separate
read timeout to set. Put the controllers most likely to be up first. Do **not** mix `ldap://`
into the list to cover a controller without TLS: every login that fails over to it sends the
password in the clear, the TLS settings are not applied to a mixed list at all, and the start-up
log warns. Fix that controller instead.

**2. Pin the certificates.** Export each controller's certificate once and put them in one
PKCS12 beside the jar. On a machine that can reach the controllers:

```powershell
# one per controller; -servername matters when a controller carries several names
openssl s_client -connect dc01.site.hnp:636 -servername dc01.site.hnp -showcerts </dev/null `
  | openssl x509 -outform PEM > dc01.cer
keytool -importcert -noprompt -alias dc01 -file dc01.cer `
  -keystore D:\logsheet\config\ad-truststore.p12 -storetype PKCS12 -storepass <password>
```

or, on the controller itself, export the certificate from the local machine store
(`certlm.msc` → Personal → Certificates → the one issued to the controller → Export, without the
private key, DER or Base-64). If the controllers' certificates were issued by an internal CA,
import that CA's certificate instead — one entry, and it survives the controllers' renewals.
Then:

```
APP_AUTH_LDAP_TRUST_SELF_SIGNED=false
APP_AUTH_LDAP_TRUSTSTORE=D:\logsheet\config\ad-truststore.p12
APP_AUTH_LDAP_TRUSTSTORE_PASSWORD=<password>
```

The JVM's own `cacerts` is not touched, and nothing goes on the command line. A truststore that
cannot be read **fails the boot** and names the file — every directory login would have failed
anyway, and a boot failure says which file where a login failure says nothing. Self-signed
certificates expire and get replaced: when a controller's changes, logins through that controller
start failing over to the others (the log shows the handshake failure) until its new certificate
is imported. Put "re-export after renewing a DC certificate" wherever that renewal is tracked.

**3. Only if you must use the balancer name, or a bare IP:** `ldaps://site.hnp:636` or
`ldaps://172.29.76.9:636` works with `APP_AUTH_LDAP_VERIFY_HOSTNAME=false` — the hostname check
is switched off, and *only* that check. The certificate is still verified against the truststore,
and since the truststore holds nothing but the controllers' own certificates, that verification
is what proves which server answered. Without a truststore the switch is refused at start-up:
encryption to a server whose identity nothing checks is not worth having. Prefer step 1 anyway; a
balancer in front of LDAP adds a hop and hides which controller misbehaved. JNDI reads this
switch once, at start-up, so changing it needs a restart.

**4. Or verify nothing.** `APP_AUTH_LDAP_TRUST_SELF_SIGNED=true` accepts any certificate and any
name — exactly what a Python `ldap3` client does with `ssl.CERT_NONE`, and the reason such a
script "just works" against the same controllers. Logins then succeed against anything that
speaks TLS on 636, including a machine on the path pretending to be a controller, which would
read every password. Take it as a conscious decision on a network you consider closed, not as
the fix for a handshake error whose real cause is one export command away (step 2). The start-up
log says so, in capitals, and the readiness check repeats it at every boot.

**What the start-up log says**, so this can be checked without a login attempt:

```
Active Directory TLS trust: D:\logsheet\config\ad-truststore.p12 (PKCS12)
Active Directory authentication: domain=site.hnp, servers=ldaps://dc01.site.hnp:636 ldaps://dc02.site.hnp:636, timeout 5000 ms per controller, trust=pinned to D:\logsheet\config\ad-truststore.p12
```

plus a WARN line if hostname verification is off, if the list mixes schemes, or if nothing is
verified. A failed login then names its cause at WARN (`Active Directory refused a.saljooghi@site.hnp
via …: wrong password (data 52e)` — the directory's own sub-code, decoded) or at ERROR
(`Active Directory is unreachable via … — local passwords still work`).

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

### The panel's CSS and JS are cached for a year, and that is safe

Static assets are served as `Cache-Control: max-age=31536000, public`. **Nothing has to be purged
on an upgrade** — a browser, and any proxy in front, keeps serving the old file at the old URL and
is never asked for it again, because every asset URL carries a hash of that file's own bytes:

```
/css/app.css   →   /css/app-105138f0d11c854f3b604bc861e33b16.css
```

A new build changes the bytes, so it changes the hash, so it changes the URL. The HTML naming
those URLs is a normal page response and is not cached, so the first request after a restart
already points at the new files.

The failure this replaced is worth recognising if you ever meet it on an older installation: an
asset served at a fixed path is answered **200 from cache** indefinitely — not a failed request,
nothing in the console, nothing in the network tab. The page simply renders with a stylesheet from
a previous deployment, and the only symptom is that it looks wrong.

If you put nginx in front and it adds its own caching for `/css/`, `/js/`, `/fonts/` or
`/webjars/`, leave it alone or make it match. **Do not** rewrite these paths to strip the hash —
that is the one change that turns this from safe into the failure above.

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
write `logs\`, `ProdLog\` and `data\`, and reach PostgreSQL — and read the Active Directory
truststore, if `APP_AUTH_LDAP_TRUSTSTORE` names one (a file the JVM cannot open fails the boot
by name).

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

# Docker — the third way to run it

Everything lives in **[`deploy/`](../deploy/)**, and it is an alternative to the systemd and
WinSW sections above rather than an addition: pick one.

| Path | Goes on | What it is |
|---|---|---|
| `deploy/Dockerfile` | the build machine | Builds the application image from a jar that already exists |
| `.dockerignore` (repository root) | the build machine | Stays at the root, because the build context is the root. Reduces that context to exactly one jar |
| `deploy/app/docker-compose.yml` | the **application** host | The application container, hardened, with its four volumes |
| `deploy/app/app.env.example` | the **application** host | Every environment variable it reads, explained |
| `deploy/postgres/docker-compose.yml` + `postgres.env.example` + `initdb/` | the **database** host | PostgreSQL, tuned, with the optional split-role setup |
| `deploy/backup/` | the **database** and **application** hosts | The nightly backup script and its systemd timer |

[`deploy/README.md`](../deploy/README.md) is the map of that directory, and the place where the
`initdb/` convention is written out in full — what runs, when, exactly once, and what must never
be put there.

**Each compose file carries its own project name** (`logsheet-app`, `logsheet-db`). Without one
compose derives it from the directory, which here would be `app` and `postgres`; since the
project name prefixes every volume and network, two stacks that both call themselves `app`
quietly share them.

> **Start here if you are deploying for the first time:** § "Docker on Linux — the complete
> runbook" below is the whole thing in order, with every command. The sections between here and
> there are the reference behind it.

## Building: two commands that do two different things

```bash
mvn -o clean package
```

**This builds the jar and nothing else.** It runs the full test suite and leaves
`target/backend-offline-first-0.0.1-SNAPSHOT.jar`. It does not know Docker exists; it produces no
image; `docker images` afterwards shows nothing new.

```bash
docker build -f deploy/Dockerfile -t logsheet-backend:1.0.0 --build-arg APP_VERSION=1.0.0 .
```

**This builds the image and compiles nothing.** It copies the jar the previous command produced
out of the build context, takes it apart into layers, and assembles the image. Run in the wrong
order it fails immediately at `COPY target/backend-offline-first-*.jar` — there is no jar to copy,
and that is the whole error.

So, one after the other:

```bash
mvn -o clean package \
  && docker build -f deploy/Dockerfile -t logsheet-backend:1.0.0 --build-arg APP_VERSION=1.0.0 .
```

Or, if you would rather compose did the second half (identical result, same Dockerfile):

```bash
mvn -o clean package && docker compose -f deploy/app/docker-compose.yml build
```

**Both commands run from the repository root**, not from `deploy/`. The `-f` only says where the
Dockerfile is; the trailing `.` is the build context, and it has to be the root because that is
where `target/` and `.dockerignore` are. Verified on 2026-10-20: the image builds from
`deploy/Dockerfile` with the context at the root, and reports `uid=10001(logsheet)` with all four
writable paths present and owned by it.

**`-t logsheet-backend:1.0.0`** is the name and tag the compose file then runs. **Tag every build
and never use `latest`**: the tag is the answer to "which version is in production", and `latest`
makes that question unanswerable. **`--build-arg APP_VERSION=1.0.0`** only fills in the image's
OCI `version` label, which is where `docker inspect` reports it; the tag is what actually selects
the image.

Carrying it to a host with no registry and no internet route:

```bash
docker save logsheet-backend:1.0.0 | gzip > logsheet-backend-1.0.0.tar.gz
# on the plant host
docker load < logsheet-backend-1.0.0.tar.gz
```

## What the image does, and why

| Choice | Reason |
|---|---|
| Two stages, the first only running `java -Djarmode=tools … extract --layers --launcher` | The four layers — dependencies, loader, snapshot-dependencies, application — mean a code change ships one small layer instead of a 93 MB jar |
| `eclipse-temurin:25-jre-noble` | A JRE, not a JDK: the compiler has no business on a plant host. Pin it by digest once you have pulled it (`docker inspect --format='{{index .RepoDigests 0}}' eclipse-temurin:25-jre-noble`) so a silent base-image change cannot arrive with a rebuild |
| `USER 10001:10001`, numeric, no shell, no home | Numeric so a Kubernetes `runAsNonRoot` check can verify it without resolving a name; fixed so volume ownership survives a rebuild |
| `curl` installed | The health probe has to ask for `/actuator/health/readiness`, which includes the database. A TCP check on 8081 comes back healthy with PostgreSQL down — the exact failure "Confirm it is actually up" above warns about |
| `-XX:MaxRAMPercentage=60` rather than `-Xmx` | The container's memory limit is the number that matters; a hard-coded heap drifts away from it the first time somebody changes one of the two. 60% of the 6 GB limit in the compose file is ~3.6 GB, which is what the README's sizing table asks for when PostgreSQL is on another host |
| `-XX:+ExitOnOutOfMemoryError` | A half-alive instance is worse than a restarted one: the health probe can still pass while every request fails |
| `/data/attachments`, `/data/imports`, `/data/template-guides`, `/logs` created and chowned in the image | So the container can run with a read-only root filesystem and still have somewhere to write — and so a fresh **named volume** inherits the right ownership |

## Host directories: `chown 10001:10001`, or it will not start

The compose file ships **named volumes** for all four writable paths, and a named volume takes its
ownership from the image — where those directories belong to uid 10001. Nothing to do.

**A bind mount does not.** A host directory keeps the ownership the host gave it, which is
normally `root:root`, and the process inside the container is uid 10001. It cannot write there.
What you see is not a permission message from the application — it is a Logback stack trace at
start-up, or an attachment upload that fails with a server error, and both read like a code
fault.

So if you map any of these to a host path — because you want the logs where `journalctl` users
expect them, or the attachments on a specific LVM volume with its own backup schedule — hand the
directories over first:

```bash
sudo mkdir -p /srv/logsheet/{attachments,imports,template-guides,logs}
sudo chown -R 10001:10001 /srv/logsheet
sudo chmod 750 /srv/logsheet/*
```

| Container path | What it holds | If you bind mount it |
|---|---|---|
| `/data/attachments` | Operators' photographs, voice notes, video. **The volume that must be backed up with the database** | `chown 10001:10001` |
| `/data/imports` | Uploaded Excel files waiting for an import job | `chown 10001:10001` |
| `/data/template-guides` | Template guide documents | `chown 10001:10001` |
| `/logs` | Logback's rotating files | `chown 10001:10001` |

and in `deploy/app/docker-compose.yml`, replace the named volume with the path:

```yaml
    volumes:
      - /srv/logsheet/attachments:/data/attachments
      - /srv/logsheet/imports:/data/imports
      - /srv/logsheet/template-guides:/data/template-guides
      - /srv/logsheet/logs:/logs
```

The uid is fixed in the Dockerfile precisely so this command can be written down once. If you
ever change it there, every bind-mounted directory needs the same `chown` again.

### PostgreSQL is the opposite case — do not `chown` it, and never `chmod 777` it

The two containers are not symmetrical, and treating them the same breaks one of them.

| | `deploy/app` | `deploy/postgres` |
|---|---|---|
| Starts as | **uid 10001 directly** (`USER 10001:10001` in `deploy/Dockerfile`) | **root**, then drops to `postgres` |
| Can it fix the directory's ownership itself? | **No.** It has no way to become anything else | **Yes.** The official entrypoint `chown`s the data directory before dropping privileges |
| A bind mount therefore needs | `chown -R 10001:10001` **before** the first start | nothing — an empty directory on a real local filesystem is enough |

So for the data directory, this is the whole procedure:

```bash
sudo mkdir -p /srv/pgdata
```

and in `deploy/postgres/docker-compose.yml`, replace the named volume with the path — mounted at
`/var/lib/postgresql`, **not** at `.../data`, for the reason the compose file explains at length:

```yaml
    volumes:
      - /srv/pgdata:/var/lib/postgresql
```

**Do not pre-`chmod` it, and above all do not `chmod 777` it "to be safe".** PostgreSQL refuses
to start on a data directory that is group- or world-accessible, and says so in a way that looks
like a corrupt volume rather than a permissions mistake:

```
FATAL:  data directory "/var/lib/postgresql/18/docker" has invalid permissions
DETAIL:  Permissions should be u=rwx (0700) or u=rwx,g=rx (0750).
```

The entrypoint sets `0700` itself. Leave it alone and it is right; widen it and the cluster will
not come up.

**Two bind mounts in that compose file are not the data directory and do follow the app's rule:**

| Host path | Container path | Written by | Needs |
|---|---|---|---|
| `./backups` | `/backups` | `pg_dump` / `pg_restore` run inside the container, as `postgres` | writable by the `postgres` uid — usually **999** |
| `./initdb` | `/docker-entrypoint-initdb.d` | nothing; **read** by the entrypoint as root | `0755`, and the scripts executable |

`mkdir -p backups` beside the compose file (which `deploy/README.md` already tells you to do)
gives a directory owned by whoever ran it. If `pg_dump` then reports `could not open file
"/backups/db.dump" for writing: Permission denied`, that is this, and the fix is one of:

```bash
# read the image's own uid rather than trusting a number from a document
docker compose exec db id -u postgres

sudo chown 999:999 backups        # or whatever the line above printed
# or, if the host directory must stay owned by a human:
sudo chmod 777 backups            # acceptable HERE — it is a dump drop-box, not a data directory
```

That last line is the one case in this document where `777` is not a mistake, and it is worth
being explicit about: a dump directory has no permission rule of its own, while the cluster
directory one line above it has a strict one.

**If you run Docker with user-namespace remapping**, neither container's entrypoint can `chown`
across the remap and both need the host directory owned by the *remapped* uid. That is a whole
host-level configuration decision, not a per-service one — decide it before the first start.

## Publishing on port 80 instead of 8081

Change the host side of the mapping — the left-hand number — and nothing else:

```yaml
    ports:
      - "80:8081"
```

**This works even though the container runs as a non-root user**, which is the part that
surprises people: the host-side socket is opened by the Docker daemon, which is root, not by the
application. The application keeps listening on 8081 inside the container, so `EXPOSE`, the
health probe and `SERVER_PORT` all stay as they are. Verified on 2026-10-18 — the binding is
accepted and `docker port` reports `8081/tcp -> 0.0.0.0:80`.

Three things to decide with it:

- **Bind it to one interface.** `"80:8081"` listens on every address the host has. Name the
  private one and the question of exposure stops depending on the firewall alone:
  ```yaml
      - "10.0.0.12:80:8081"
  ```
- **Do not do this on the nginx host.** In the three-host topology nginx owns 80 and 443; the
  application is on its own machine, where 80 is free. If you ever collapse the two onto one
  host, nginx keeps 80 and the application keeps 8081.
- **It changes nothing about TLS.** Port 80 is still plain HTTP. If browsers are to reach the
  panel directly on it, read §17 in [roadmap.md](roadmap.md) first — that is the decision about
  administrator credentials in cleartext, and it does not go away by renumbering the port.

If instead you want the application *itself* to listen on 80 inside the container, set
`SERVER_PORT=80` in `app.env` and change `EXPOSE`/the health probe to match. There is no reason
to: the published port is what the outside world sees either way, and 8081 inside keeps the
container identical to every other way of running this application.

## The application compose file, and the two traps in it

`read_only: true`, `cap_drop: ALL`, `no-new-privileges`, `user: "10001:10001"` and a `tmpfs` for
`/tmp` (Tomcat stages multipart uploads there, so it is sized above the 50 MB request ceiling).
Every key in the file carries a comment explaining what it is for; the two worth repeating here:

**Logs are a named volume, not a bind mount** — see the section above for why, and for the
`chown` if you want it on the host anyway.

**`replicas: 1`, and nothing that scales it.** `docker compose up -d` is safe: it stops the old
container before starting the new one. `docker compose up --scale app=2` is not, and neither is a
second `docker run` beside it. Log sheet generation defends itself now (a losing instance rolls
its rounds back and logs a warning), but the three storage directories are not shared and a
second instance starting will mark the import the first one is *running* as failed and delete its
uploaded file. [roadmap.md §19](roadmap.md) is the full inventory.

## Environment

Everything environment-specific arrives as an environment variable; nothing is baked into the
image. Copy `deploy/app/app.env.example` to `app.env` beside it, fill it in, `chmod 600` it —
`*.env` is already in
`.gitignore`. The example file documents every variable it lists, grouped as: the five settings
the application warns about at every boot, the datasource, the four paths, `TZ`, and the optional
knobs (`SPRING_PROFILES_ACTIVE=json-logs`, `SERVER_FORWARD_HEADERS_STRATEGY`, the scheduler
intervals).

**`TZ=Asia/Tehran` is not optional.** Reports and the scheduler pin the zone in code, so their
numbers are safe either way — but the attachment directory is sharded by `LocalDate.now()` and
Logback stamps lines with the system zone. A container without `TZ` runs on UTC, which puts
photographs in yesterday's folder and every log line three and a half hours out.

Note the difference between the two kinds of variable in a compose setup: `env_file:` entries go
**into the container** (the application reads them), while `${...}` substitutions inside the
compose file itself — `APP_VERSION` is the only one here — are read **by compose**, from the
shell or a `.env` file beside it. Putting `APP_VERSION` in `app.env` would do nothing.

## PostgreSQL on its own host

`deploy/postgres/docker-compose.yml` with `postgres.env.example`, and an `initdb/` directory
for the one-time role setup. It is deliberately a separate project on a separate machine: a
database that shares a compose project with the application is a database somebody restarts by
accident — and `docker compose down -v` in the wrong directory is the end of the plant's data.

```bash
cd deploy/postgres
cp postgres.env.example postgres.env && chmod 600 postgres.env   # fill in POSTGRES_PASSWORD
mkdir -p backups
docker compose up -d
```

**The data volume mounts at `/var/lib/postgresql`, not at `/var/lib/postgresql/data`.** This is
not interchangeable and it is the first thing that will bite: from version 18 these images keep
the cluster in a major-version-specific subdirectory so a later `pg_upgrade --link` does not have
to cross a mount boundary, and a volume at the old path is refused outright —

```
Error: in 18+, these Docker images are configured to store database data in a
       format which is compatible with "pg_ctlcluster" …
       Counter to that, there appears to be PostgreSQL data in:
         /var/lib/postgresql/data (unused mount/volume)
```

Every server setting is a `-c` flag in the compose file with a comment saying why, tuned for the
4 vCPU / 8 GB host the README's sizing section recommends: `shared_buffers=2GB`,
`effective_cache_size=6GB`, `work_mem=16MB`, `maintenance_work_mem=512MB`, `max_connections=100`,
SSD costs (`random_page_cost=1.1`, `effective_io_concurrency=200`), WAL sizing, and —
operationally the most useful one — `log_min_duration_statement=1000`, which is how a slow page
gets diagnosed without turning on full statement logging.

Two settings that can only be chosen **before the first start**, because they are properties of
`initdb`:

- **`--data-checksums`** (in `POSTGRES_INITDB_ARGS`): PostgreSQL verifies every page it reads, so
  silent disk corruption becomes an error instead of a wrong number years later. Turning it on
  afterwards means a dump and restore.
- **Collation.** Left at the image default on purpose. The `ux_*_lower` indexes use `lower()`,
  which is identity for Persian letters, so uniqueness is correct either way; `ORDER BY` on
  Persian text then sorts by code point, which is close to alphabetical but not exact. If a site
  ever decides it must be exact, that is an `initdb` decision too — and it has to match any dump
  you intend to restore.

**`initdb/` runs once.** The entrypoint executes its scripts in filename order the first time the
data volume is initialised, and skips the directory entirely ever after — silently, with no log
line you would notice. [`deploy/README.md`](../deploy/README.md) has the full rules, including
the `.sh` executable-bit difference that decides whether a script is *run* or *sourced into the
entrypoint's own shell*, and why **restoring a dump does not belong in here**.
`initdb/10-app-role.sh.example`
creates the README's split-role setup — a `NOLOGIN` schema owner that migrations run as, and a
DML-only user the application connects as, which cannot create or drop anything even if something
goes very wrong. Copy it to `initdb/10-app-role.sh` and set `APP_DB_USER`/`APP_DB_PASSWORD` in
`postgres.env` to use it; leave them empty and the simpler single-role setup stands, which is what
the development database does. **With the split role, the application must start with
`SPRING_FLYWAY_ENABLED=false`** and migrations must be run once per release by the owner —
otherwise it fails with `42501`, because Flyway creates its own history table first.

**Publishing.** The file publishes on `${DB_BIND_IP:-127.0.0.1}:5432` so that a stack nobody
configured exposes nothing. Set the private address the application host reaches this machine on
in a `.env` file beside the compose file — **not** in `postgres.env`, which compose does not read
for substitution:

```bash
# deploy/postgres/.env
DB_BIND_IP=10.0.0.11
```

> ⚠ **A published port bypasses ufw.** Docker writes its own DNAT rules into the `nat` table and
> they are evaluated before ufw's `INPUT` chain ever sees the packet, so `ufw deny 5432` does not
> close a port published on `0.0.0.0` and `ufw allow from <ip>` is not what is keeping anyone
> out. Binding to one address is the control that actually works. If you want a rule as well, it
> has to go in the chain Docker leaves for exactly this:
>
> ```bash
> iptables -I DOCKER-USER -p tcp --dport 5432 ! -s <application-host-ip> -j DROP
> ```
>
> The same is true of the application's published port on its own host.

Backups are unchanged in substance — "Backups — one job, both halves" below, and the Docker
variant of `pg_dump` that writes inside the container and copies the file out. The `./backups`
mount in the compose file is there so that dump lands on the host.

## Docker on Linux — the complete runbook

Ubuntu 22.04/24.04, the three-host topology, start to finish. Two of the hosts are built here;
nginx is the third and is not covered (see § Related).

Throughout: **`10.0.0.11` is the database host and `10.0.0.12` is the application host.**
Substitute your own and nothing else changes.

### 0. On the build machine — the image

The plant hosts have no internet route, so the image is built where there is one and carried
over as a file.

```bash
cd <this checkout>
mvn -o clean package
docker build -f deploy/Dockerfile -t logsheet-backend:1.0.0 --build-arg APP_VERSION=1.0.0 .
docker save logsheet-backend:1.0.0 | gzip > logsheet-backend-1.0.0.tar.gz
```

Carry over three things: that tarball, the `deploy/` directory, and nothing else. The jar is
inside the image; the source tree is needed on neither host.

### 1. Both hosts — Docker itself

The `docker-compose-plugin` is what provides `docker compose` (two words). The old
`docker-compose` binary is a different, unmaintained program, and some of the keys in these
files behave differently under it.

```bash
# Docker's own repository, not Ubuntu's — Ubuntu's docker.io lags and omits the compose plugin
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

sudo systemctl enable --now docker
docker compose version          # must print v2.x
```

`restart: unless-stopped` only survives a reboot if the daemon itself starts at boot, which is
what `enable` above is for. Confirm with `systemctl is-enabled docker`.

### 2. Database host (10.0.0.11)

```bash
sudo mkdir -p /opt/logsheet
sudo cp -r deploy /opt/logsheet/
cd /opt/logsheet/deploy/postgres
sudo mkdir -p backups
```

**The environment file**, which holds the superuser password:

```bash
sudo cp postgres.env.example postgres.env
sudo chmod 600 postgres.env
openssl rand -base64 36          # paste into POSTGRES_PASSWORD
sudo nano postgres.env
```

**The address it publishes on** — a separate file, because compose reads this one about itself
rather than passing it into the container:

```bash
printf 'DB_BIND_IP=10.0.0.11\n' | sudo tee .env
```

**The split role**, if you want the application to run without DDL rights (recommended; the
README's "Two database roles" explains the trade):

```bash
sudo cp initdb/10-app-role.sh.example initdb/10-app-role.sh
sudo chmod 755 initdb/10-app-role.sh      # executable, or the entrypoint sources it instead
# then set APP_DB_USER and APP_DB_PASSWORD in postgres.env
```

Leave `APP_DB_USER`/`APP_DB_PASSWORD` empty and the script does nothing, which is the single-role
setup: the application connects as the superuser and Flyway migrates on every boot.

**Start it**, and watch the first boot — this is the only time `initdb/` ever runs:

```bash
sudo docker compose up -d
sudo docker compose logs -f db
```

What a correct first boot says, in order: `initdb` creating the cluster, then
`/docker-entrypoint-initdb.d/10-app-role.sh` if you installed it, then `database system is ready
to accept connections`. Then:

```bash
sudo docker compose ps                     # STATUS must reach (healthy), not merely Up
sudo docker compose exec db psql -U postgres -d offline_first_db \
  -c "SHOW timezone" -c "SHOW password_encryption" -c "SHOW data_checksums"
```

Expected: `Asia/Tehran`, `scram-sha-256`, `on`.

**The firewall.** Binding to `10.0.0.11` already keeps the port off every other interface. ufw
cannot add to that for a *published* port — see the warning in § "PostgreSQL on its own host" —
so the rule that matters goes in `DOCKER-USER`, and ufw is left to protect SSH and anything not
published by Docker:

```bash
sudo iptables -I DOCKER-USER -p tcp --dport 5432 ! -s 10.0.0.12 -j DROP
sudo apt-get install -y iptables-persistent && sudo netfilter-persistent save
```

Without `netfilter-persistent` that rule is gone at the next reboot, and nothing says so.

### 3. Application host (10.0.0.12)

```bash
sudo mkdir -p /opt/logsheet
sudo cp -r deploy /opt/logsheet/
docker load < logsheet-backend-1.0.0.tar.gz
docker images logsheet-backend          # confirm the tag arrived
cd /opt/logsheet/deploy/app
```

**The environment file:**

```bash
sudo cp app.env.example app.env
sudo chmod 600 app.env
openssl rand -base64 48                 # paste into APP_AUTH_JWT_SECRET
sudo nano app.env
```

The four that must be right before the first start:

| Variable | Value |
|---|---|
| `APP_AUTH_JWT_SECRET` | the 48 bytes you just generated — never the repository's |
| `APP_CORS_ALLOWED_ORIGINS` | the PWA's origin, or **empty** when nginx serves it same-origin. Empty and commented out are opposites here: commenting the line out means `*` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://10.0.0.11:5432/offline_first_db` — the host's real address, never `host.docker.internal` |
| `TZ` | `Asia/Tehran`. Not optional: the attachment directory is sharded by `LocalDate.now()` |

**The address it publishes on:**

```bash
printf 'APP_VERSION=1.0.0\nAPP_BIND_IP=10.0.0.12\nAPP_PORT=8081\n' | sudo tee .env
```

**Start it:**

```bash
sudo docker compose up -d
sudo docker compose logs -f app
```

### 4. Confirm it is actually up

Four checks, and the order matters — each rules out a different layer:

```bash
# 1. Docker's own probe, which asks /actuator/health/readiness from INSIDE the container.
#    (healthy) therefore means the application answered AND the database was reachable.
sudo docker compose ps

# 2. The boot block. If this prints anything at all, app.env is not finished.
sudo docker compose logs app | grep -A6 "SHIPPED DEFAULTS"

# 3. Flyway. "Successfully applied" on a new database, "Successfully validated" after that.
sudo docker compose logs app | grep -i flyway

# 4. From the nginx host, not this one — proving the publish address and the firewall together.
curl -fsS http://10.0.0.12:8081/actuator/health/readiness
```

Expected from the last: `{"status":"UP","groups":["liveness","readiness"]}`.

> **`Schema "public" has a version (5) that is newer than the latest available migration (4)`**
> is a WARNING, and the application starts anyway. It means this jar is pointed at a database
> that some other build already migrated — a leftover development cluster, or the wrong host in
> `SPRING_DATASOURCE_URL`. `ddl-auto=validate` can still pass if the entities happen to match,
> so nothing else will tell you. Stop and find out which database you are on before any operator
> enters a reading into it.

### 5. Upgrades

```bash
# build machine
mvn -o clean package
docker build -f deploy/Dockerfile -t logsheet-backend:1.0.1 --build-arg APP_VERSION=1.0.1 .
docker save logsheet-backend:1.0.1 | gzip > logsheet-backend-1.0.1.tar.gz

# application host
docker load < logsheet-backend-1.0.1.tar.gz
cd /opt/logsheet/deploy/app
sudo sed -i 's/^APP_VERSION=.*/APP_VERSION=1.0.1/' .env
sudo docker compose up -d                # stops the old container, starts the new one
sudo docker compose logs -f app
```

**Rollback is the same two lines with the old tag**, which is the reason every build is tagged
and `latest` is never used. Keep the previous image on the host (`docker images`) until the new
one has survived a day; `docker image prune` is for later.

**Take a backup before an upgrade that carries a migration.** Flyway's forward migrations have no
down scripts, so rolling the image back does not roll the schema back — a restore is the only way
back. Check whether a release adds one before you start:

```bash
docker run --rm --entrypoint ls logsheet-backend:1.0.1 BOOT-INF/classes/db/migration
```

### 6. Stopping, and the one command to be careful with

```bash
sudo docker compose stop         # take it down on purpose; restart: unless-stopped respects this
sudo docker compose start
sudo docker compose down         # removes the containers, KEEPS the volumes
sudo docker compose down -v      # ⚠ removes the volumes too — on the database host that is the
                                 #   plant's data, and on the application host it is every
                                 #   photograph an operator has ever taken
```

There is no confirmation prompt on `-v` and no undo. The backup in the next section is what
stands between that keystroke and a very bad week.

## What was verified, and on what

Built and run on 2026-10-18 against a throwaway PostgreSQL 18.4 container, then torn down:

- the image builds from the committed jar, and the four extracted layers copy in order;
- the application container reaches `healthy` — Docker's own probe, so
  `/actuator/health/readiness` answered UP **from inside**, with the database attached;
- `id` reports `uid=10001(logsheet)`, and `touch /nope` is refused by the read-only root;
- Flyway applied **all four migrations to an empty schema** from scratch;
- the boot block printed its warnings, including the empty-CORS line;
- publishing on port 80 is accepted with the non-root container;
- the database stack comes up `healthy` with `data_checksums=on`, `timezone=Asia/Tehran`,
  `encoding=UTF8`, and the tuning flags applied as written;
- the split-role script creates both roles, transfers ownership of schema `public` to
  `offline_owner`, and **`offline_app` is refused when it tries to create a table** — which is
  the whole point of it.

Not verified here: nothing about nginx, TLS or the PWA. Those are the next step.

> **On a Windows workstation**, pass the environment with `--env-file` or compose rather than
> `-e APP_LOG_PATH=/logs`: Git Bash rewrites a leading-slash value into a Windows path
> (`C:/Program Files/Git/logs`) before Docker ever sees it, and the application then fails to
> open its log file. Irrelevant on the Ubuntu hosts this is for, and confusing for ten minutes
> on the machine the image is built on.

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
`Start-ScheduledTask`; the transcript at `D:\Backup\logsheet
uns\<stamp>ackup.log` names the
exact failure.

> **Save this file as ASCII, or as UTF-8 *with* a BOM — and keep it free of non-ASCII
> characters.** Windows PowerShell 5.1 assumes the system ANSI code page for a `.ps1` that has no
> BOM, so a UTF-8 file saved without one is decoded as Windows-1252. An em dash (`—`, bytes
> `E2 80 94`) then becomes `â€”`, whose last character is `”` — and **PowerShell accepts `”` as a
> string delimiter**. One em dash inside a double-quoted string ends that string early and the
> rest of the file misparses:
>
> ```text
> The string is missing the terminator: ".
> Missing closing '}' in statement block or type definition.
> The Try statement is missing its Catch or Finally block.
> ```
>
> The errors point at the end of the file, never at the character that caused them. The script
> below is deliberately pure ASCII so the question cannot arise. To check any script:
>
> ```powershell
> $e = $null
> [void][System.Management.Automation.Language.Parser]::ParseFile(
>     'D:\MyApp\scripts\backup-logsheet.ps1', [ref]$null, [ref]$e)
> $e   # empty means it parses
> ```

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

    # 2) Attachments second, into this run's own folder - a full copy.
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

    # 4) Rotation - whole run folders.
    #    Age comes from the folder NAME, not its timestamp: a directory's LastWriteTime
    #    changes whenever anything inside it does. A name that does not parse is left
    #    alone, so nothing unexpected in this directory is ever deleted - including the
    #    run this script is writing right now.
    $cutoff = (Get-Date).AddDays(-$KeepDays)
    Get-ChildItem $Dest -Directory | Where-Object {
        $parsed = [datetime]::MinValue
        [datetime]::TryParseExact($_.Name, 'yyyy-MM-dd_HHmmss', $null,
            [Globalization.DateTimeStyles]::None, [ref]$parsed) -and $parsed -lt $cutoff
    } | Remove-Item -Recurse -Force

    Write-Output "BACKUP OK $stamp - $Run"
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

> These are the ad-hoc commands, for a one-off dump or for working something out by hand. The
> scheduled job — script, systemd timer, rotation and the hardlink chain — is the next section,
> § "Backups — Docker on Linux", and it ships in [`deploy/backup/`](../deploy/backup/).

**These commands carry no password on purpose.** `docker exec` connects over the container's
local socket, and the official `postgres` image ships `local all all trust` in its generated
`pg_hba.conf` — so a local connection needs no credentials at all. That is a property of the
image, not a guarantee: if you harden `pg_hba.conf`, put a `.pgpass` inside the container (or
mount one) and keep `--no-password` so the job fails loudly instead of waiting for a prompt.
Passing `-e PGPASSWORD=…` to `docker exec` works but writes the password into
`docker inspect` output and the host process list — prefer the file.

```bash
CONTAINER=logsheet-db               # docker ps --format '{{.Names}}'
DEST=/backup/logsheet
stamp=$(date +%F_%H%M%S)
RUN="$DEST/$stamp"; mkdir -p "$RUN"

# Write the dump inside the container, then copy it out. This avoids every
# stream-mangling problem a pipe or a TTY can introduce.
docker exec "$CONTAINER" \
    pg_dump -U postgres -d offline_first_db \
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
> docker exec -i "$CONTAINER" pg_dump -U postgres -d offline_first_db -Fc > db.dump
> ```

Two more container-specific rules:

- **Dump with the container's own `pg_dump`, not the host's.** A host client older than the
  server refuses to run (`server version mismatch`), and the container's binaries always match
  its server. This is a reason to prefer `docker exec` even when a host client exists.
- **`-U postgres` over the local socket needs no password**, because the image's generated
  `pg_hba.conf` has `local all all trust`. The dedicated read-only `logsheet_backup` role the
  host sections use is still the better choice when you dump over TCP from another machine;
  inside the container it buys nothing and costs a `.pgpass` to mount.
- **Never back up the data volume by copying it while the server runs.** `PGDATA` copied live is
  torn and unusable. If you want a physical backup, use `pg_basebackup`; otherwise `pg_dump` is
  the whole story.

If the **application** is also containerised, the attachments live in a volume rather than on the
host, so mirror them through a throwaway container — `logsheet-app` is the compose project
name and `attachments` the volume key, so check `docker volume ls` if you renamed the project:

```bash
docker run --rm \
    -v logsheet-app_attachments:/data:ro \
    -v "$DEST/attachments":/mirror \
    alpine sh -c 'cp -a /data/. /mirror/'
```

On Windows with Docker Desktop, the same script works from PowerShell with `docker.exe`; keep the
`--file` / `docker cp` shape rather than piping, for the reason above.

---

## Backups — Docker on Linux

The section above is the commands. This is the packaged job: a script, a systemd timer, and the
three things that have to exist before either of them works.

It is the container equivalent of § "Backups — Linux", and it keeps that section's two
properties, which are the ones worth keeping:

- **Every run gets its own folder**, named by timestamp, holding the dump, the attachments and
  the run's own log. A run can neither disturb nor be confused with any other.
- **Unchanged attachments are hardlinked to the previous run** (`rsync --link-dest`), so thirty
  complete daily trees cost about as much disk as one plus thirty days of difference.

Two things are genuinely different under Docker, and both are why the host script cannot just be
reused:

- **`pg_dump` runs inside the container.** A host client one major version behind the server
  refuses outright (`server version mismatch`), and the container's binaries always match its
  server. It also means no password anywhere: the connection goes over the container's local
  socket, which the image's generated `pg_hba.conf` trusts (`local all all trust`).
- **The attachments are in a named volume**, not a host directory. A named volume *is* a
  directory on the host, but its path belongs to Docker — ask for it with
  `docker volume inspect -f '{{ .Mountpoint }}'` rather than hardcoding `/var/lib/docker/...`,
  which is right until somebody changes the storage driver or the data-root.

### The two halves are on two hosts

In the three-host topology the database is on one machine and the attachments are on another, so
the backup is two jobs, not one. The script handles both and takes a switch for which half to
run; install it on both hosts and turn off the half that is not there.

| Host | Setting | Produces |
|---|---|---|
| Database (10.0.0.11) | `DO_FILES=0` | `<run>/db.dump` |
| Application (10.0.0.12) | `DO_DB=0` | `<run>/attachments/` |
| Single-host deployment | neither | both, in one run folder |

**Collect the two halves to one place.** A dump without the photographs it refers to is not a
restorable system. Whatever carries run folders off these machines — the weekly copy to the NAS
in § Retention — has to take both, and the timestamps have to be close enough that they pair.
Running both timers at `01:00` with the same `RandomizedDelaySec` does that well enough for this
system's write rate; if you want them exact, run the application host's at `01:05` and accept
that its folder is five minutes newer.

### Before the first run

| | What | Check it with |
|---|---|---|
| 1 | Docker is up and the stack is running on this host | `docker compose ps` shows `(healthy)` |
| 2 | `/backup/logsheet` exists, on a **different disk** from the data | `df /backup/logsheet /var/lib/docker` — two different devices |
| 3 | On the database host: the `backups` bind mount exists and the container can write to it | `docker compose exec db touch /backups/.probe` |
| 4 | On the application host: the volume name is right | `docker volume ls \| grep attachments` |

The volume name is the compose **project** name plus the volume key: `logsheet-app` +
`attachments` = `logsheet-app_attachments`. If you renamed the project, this changes with it.

### Install

```bash
sudo install -m 755 -o root -g root \
    /opt/logsheet/deploy/backup/logsheet-backup.sh /usr/local/bin/logsheet-backup.sh
sudo install -m 644 \
    /opt/logsheet/deploy/backup/logsheet-backup.service \
    /opt/logsheet/deploy/backup/logsheet-backup.timer /etc/systemd/system/

# Which half this host runs — edit the Environment= lines, they ship commented out
sudo systemctl edit --full logsheet-backup.service

sudo mkdir -p /backup/logsheet
sudo systemctl daemon-reload
sudo systemctl enable --now logsheet-backup.timer
```

Test it now rather than finding out at 01:00:

```bash
sudo systemctl start logsheet-backup.service
journalctl -u logsheet-backup.service -n 40 --no-pager
sudo ls -l /backup/logsheet/
systemctl list-timers logsheet-backup.timer      # when it next fires
```

The last line of a good run is `BACKUP OK <stamp> — /backup/logsheet/<stamp>`. Anything else and
the run folder's own `backup.log` has the rest.

**The job runs as root, and that is not laziness.** It needs the Docker socket, and membership of
the `docker` group is equivalent to root on that host anyway — a dedicated non-root user here
would buy nothing and would obscure what the job can actually do. It also has to read a named
volume whose contents belong to uid 10001.

### What the script does, in order

1. **The database first.** A reading row pointing at an attachment that is not in the backup is a
   broken link you can see and investigate; an attachment with no row is an orphan nobody ever
   finds. Dumping first makes the file half a superset, which is the recoverable direction.
2. **Verify the dump, in the container, immediately.** `pg_restore --list` is cheap and it is the
   only thing that catches a zero-byte or truncated file — on the day it was written rather than
   on the day it is needed.
3. **Move it out of `/backups`** into the run folder. The shared directory is where the container
   can write; it is not where backups live, because the next run would write over it.
4. **The attachments**, hardlinked against the previous run.
5. **Rotate whole run folders** older than `KEEP_DAYS` (30). `rm` removes that run's links; the
   bytes survive as long as any remaining run still links to them, which is what makes the chain
   safe to prune from either end.

### Restoring, for this layout

Into a **new, empty** stack — which is also how you rehearse it, and § "The monthly drill" below
says to:

```bash
cd /opt/logsheet/deploy/postgres
sudo docker compose down -v            # ⚠ only on a host you intend to wipe
sudo docker compose up -d              # fresh cluster; initdb/ runs again and recreates the roles
sudo docker compose ps                 # wait for (healthy)

sudo cp /backup/logsheet/<stamp>/db.dump backups/restore.dump
sudo docker compose exec -T db \
    pg_restore --username=postgres --dbname=offline_first_db \
               --no-owner --no-acl --exit-on-error --verbose /backups/restore.dump
sudo rm backups/restore.dump
```

`--no-owner --no-acl` because the roles on the new cluster are the ones `initdb/` just created,
not necessarily the ones the dump remembers. `--exit-on-error` because a restore that reports
success after skipping forty statements is worse than one that stops.

And the attachments, back into the volume:

```bash
sudo docker run --rm \
    -v logsheet-app_attachments:/dst \
    -v /backup/logsheet/<stamp>/attachments:/src:ro \
    alpine sh -c 'cp -a /src/. /dst/ && chown -R 10001:10001 /dst'
```

The `chown` is the step people miss: files copied in arrive owned by root, and the application is
uid 10001. It will serve nothing and the error reads like a code fault.

Then start the application and check § "Verifying a restore" below — the row counts, not just
that it booted.

#### Restore as the superuser or the schema owner — never as the application's login role

`--username=postgres` above is not decoration, and this is the step that is easiest to
improvise past.

**Since PostgreSQL 15, `PUBLIC` no longer holds `CREATE` on schema `public`.** On 16/17/18 a
plain login role therefore cannot create a table there unless it owns the schema or was granted
`CREATE` explicitly. Both of this repository's setups put the application on exactly such a
role: the split-role setup gives it `USAGE` only and leaves `public` owned by `offline_owner`
([deploy/README.md](../deploy/README.md), `initdb/10-app-role.sh.example`), and the simple setup
connects as the superuser only because nothing else was configured.

Restore as the application user and **every `CREATE TABLE` is refused**, which then makes every
`ALTER TABLE`, `CREATE INDEX` and `COPY` behind it fail for a table that was never built. The
log is a wall of consequences with the cause near the top:

```
ERROR:  permission denied for schema public at character 14      ← the cause, once per table
ERROR:  relation "public.log_sheet_entries" does not exist       ← the consequences, hundreds
WARNING:  no privileges were granted for "public"                ← the dump's own GRANT, no-op
```

"at character 14" is where the table name sits in `CREATE TABLE public.x`. The `WARNING` is the
same problem from the other side: a dump carries `GRANT … ON SCHEMA public`, and a role that
does not own the schema grants nothing.

Seen on a real restore on 2026-10-31: 393 errors in one minute, 37 of them the cause and 356 the
consequence, across 25 tables.

If you must restore as a non-superuser, hand it the schema first. There are three ways and
**the first one is almost always the right answer**.

**Make the role own the database** — one command, and nothing about `public` to think about:

```bash
# the database does not exist yet
docker exec logsheet-db psql -U postgres \
    -c "CREATE DATABASE offline_first_db OWNER offline_first_app;"

# or it already exists and you do not want to drop it
docker exec logsheet-db psql -U postgres \
    -c "ALTER DATABASE offline_first_db OWNER TO offline_first_app;"
```

**Why that is enough, and why it is not obvious.** Since PostgreSQL 15 the `public` schema is
owned by the special role **`pg_database_owner`**, whose only member is — implicitly — whoever
owns the database. So owning the database *is* owning `public`, without naming it. Verified on
`postgres:18.4`: in a database created `OWNER appuser` that role creates tables freely; in one
created by the entrypoint (owner `postgres`) the same role gets `ERROR: permission denied for
schema public`, with `public` owned by `pg_database_owner` in **both**. That difference — who
owns the database, not who owns the schema — is the whole bug.

The two below are for the narrower case where the role must create in `public` but must **not**
own the database.

**Grant the two privileges** — the minimum, and enough whenever the restore strips ownership and
ACLs (`pg_restore --no-owner --no-acl`, as the command above does, or a `pg_dump` taken with the
same flags):

```bash
docker exec -it logsheet-db psql -U postgres
```
```sql
GRANT CONNECT ON DATABASE offline_first_db TO offline_first_app;
\c offline_first_db
GRANT USAGE, CREATE ON SCHEMA public TO offline_first_app;
```

The `\c` is the step people miss and it is not optional: **`GRANT … ON SCHEMA public` applies to
the schema of the database you are currently connected to.** Run it from `postgres` and you have
granted rights on the wrong `public`, the command reports `GRANT`, and the restore fails exactly
as before. `GRANT CONNECT` is usually already satisfied — `PUBLIC` holds it unless somebody
revoked it — but it belongs in the recipe for a freshly created role.

**Or transfer ownership** — heavier, and what you need when the dump carries its own `GRANT` and
`ALTER … OWNER TO` statements, because only an owner may execute those:

```bash
docker exec logsheet-db psql -U postgres -d offline_first_db \
    -c "ALTER SCHEMA public OWNER TO offline_first_app;"
```

The tell is in the log: `GRANT USAGE, CREATE` clears every `permission denied for schema public`,
but a dump replaying its own ACLs still answers `WARNING: no privileges were granted for
"public"`, and any `ALTER … OWNER TO` in it still fails. If you see that warning, you wanted
ownership — and if the role may own the database, owning *that* is the simpler way to get it.

For the split-role setup, none of this applies: `public` is already owned by `offline_owner`
(`initdb/10-app-role.sh.example`). Restore as a member of that role, not as the DML-only user,
and leave its privileges alone.

#### A plain-SQL dump needs `ON_ERROR_STOP` spelled out

`--exit-on-error` is a `pg_restore` flag, so it applies to a custom-format dump (`-Fc`) only. A
`.sql` dump replayed through `psql` **ignores errors by default** and exits 0 after any number of
them:

```bash
psql -v ON_ERROR_STOP=1 --username=postgres --dbname=offline_first_db -f dump.sql
```

Without it a half-built database reports success, Flyway finds whatever did get created, and the
application boots cleanly against it. That is the failure mode § "Verifying a restore" exists to
catch — run it, and do not treat "it booted" as the answer.

> **Check which migrations the dump carries before pointing a new jar at it.** The dump includes
> `flyway_schema_history`, so a restored database remembers exactly what was applied to it, with
> checksums. If the jar's migration files have changed since — a development migration edited
> after it was applied, which this project has done deliberately once — Flyway refuses to start
> with a checksum mismatch. Read the history out of the dump without restoring it:
>
> ```bash
> docker run --rm -v /backup/logsheet/<stamp>:/b:ro postgres:18.4 \
>     pg_restore --data-only -t flyway_schema_history -f - /b/db.dump | grep '^[0-9]'
> ```

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
