# Open issues — review of 2026-11-16

A read-only review of this backend and its companion PWA
(`H:\Project\offline-first-project\offline-first-pwa`) for **security, data loss, bugs and
performance**, plus the production container images. The PWA's half is in that repository's
`docs/issue.md`; the two files cross-reference each other by id (`B-nn` here, `P-nn` there).

**How it was done, and what that means for trusting it.** By reading the code. Nothing was run:
no test suite, no live instance, no dependency scan, no measurement. Every entry names the lines
it was read from, so each can be checked in a minute. An entry that could not be confirmed from
code alone says so.

**Fixed since:** B-01, B-02, B-03 and B-04 §1 (V25, 2026-11-17), B-05 (2026-11-17), and B-04 §2–§4, B-09, B-10, B-11 and B-12 (V26/V27, 2026-11-18), and the `completedAt` half of B-17 (V28, 2026-11-18) — each entry says how. The rest carry a proposed fix that is not applied. When one is,
the reference documents change in the same commit (CLAUDE.md, "The rule that keeps this useful")
and the entry is marked `Fixed` with the commit, not deleted.

## Summary

| Id | Severity | Kind | Title | Status |
|---|---|---|---|---|
| [B-01](#b-01) | High | Data loss | A round completed after its deadline is refused and **not kept** | **Fixed (V25)** |
| [B-02](#b-02) | High | Data loss | Readings taken before the deadline but delivered after it are never accepted | **Fixed (V25)** — kept, not applied; `extend` makes them stand |
| [B-03](#b-03) | Medium | Data loss | A replayed, already-voided submission is answered `DUPLICATE` | **Fixed (V25)** |
| [B-04](#b-04) | Medium | Security | `submitOne` has no access check: any operator can write void rows and evidence files onto any sheet | **Fixed** — §1 in V25, §2–§4 in V26 (2026-11-18) |
| [B-05](#b-05) | Medium | Data loss | Attachment files are deleted before the transaction commits | **Fixed (2026-11-17)** |
| [B-06](#b-06) | Medium | Security | Login lockout is a denial of service, and discloses real usernames; nothing is rate limited | **Partly mitigated** — nginx limits sign-in per address since `mfdcs-pwa:1.0.2`; the code changes are open |
| [B-07](#b-07) | Medium | Deployment | Shipped defaults (JWT key, DB password, `admin123`, CORS `*`, LDAP trust-all) only warn | **Won't fix in code** — a deployer's step, `deployment.md` «The settings that must not stay as they ship» |
| [B-08](#b-08) | Medium | Deployment | The panel is plain HTTP on 8081; no forwarded-header handling, no `Secure` cookie | Open |
| [B-09](#b-09) | Medium | Bug | NFC fault reports are one transaction per batch — one bad item fails all, forever | **Fixed (2026-11-18)** |
| [B-10](#b-10) | Low | Security | An attachment id can be taken by a colleague before its bytes arrive | **Fixed (2026-11-18)** — with the PWA's P-06 |
| [B-11](#b-11) | Low | Security | `returnTo` is an open redirect on four handlers; raw exception text reaches the page | **Fixed (2026-11-18)** |
| [B-12](#b-12) | Low | Security | Device enrolment: status disclosed by id at login; the pending cap can be filled | **Fixed (V27, 2026-11-18)** — the address-level rate limit is nginx's |
| [B-13](#b-13) | Low | Bug | `expireOverdueSheets` wraps its whole loop in one transaction | **Fixed (2026-11-22)** |
| [B-14](#b-14) | Low | Performance | Every `@Scheduled` job shares one thread; the sweep and the purges run inline on it | **Fixed (2026-11-22)** — option 2: the thread stays single, the long work has its own |
| [B-15](#b-15) | Low | Performance | An attachment download buffers the whole file and cannot serve a range | **Fixed (2026-11-22)** — streamed, `Range` answered; not yet checked on a tablet or a browser |
| [B-16](#b-16) | Low | Bug | JSON ceiling is 64 MB here and 32 MB at nginx | **Fixed (2026-11-22)** — both 64 MB, documented as a pair |
| [B-17](#b-17) | Info | Integrity | `completedAt` and `entrySource` are the device's word; NFC lookup is plant-wide | **`completedAt` hardened (V28, 2026-11-18)** — the rest accepted, restated |
| [B-18](#b-18) | Low | Bug | A minimal hand-written `.xlsx` fails the batch import but passes the direct one | **Fixed (2026-11-22)** — `countDataRows` opens read-only |
| [D-01](#d-01--the-image-boots-with-development-defaults-medium) | Medium | Deployment | The image boots with development defaults | **Won't fix in code** — follows B-07 |
| [D-02](#d-02--the-database-link-is-clear-text-when-the-two-hosts-differ-medium) | Medium | Deployment | Database link is clear text across hosts | Open |
| [D-03](#d-03--bases-are-pinned-by-tag-not-by-digest-low) | Low | Deployment | Base pinned by tag, not digest | **Fixed in 1.0.2** (backend; PostgreSQL image unchanged) |
| [D-04](#d-04--apt-get-upgrade-makes-a-build-unrepeatable-low-accepted) | Low | Deployment | `apt-get upgrade` makes a build unrepeatable | Accepted |
| [D-05](#d-05--curl-is-in-the-image-only-for-the-health-check-low) | Low | Deployment | `curl` in the image for the health check alone | **Fixed in 1.0.2** |
| [D-06](#d-06--the-database-container-keeps-its-default-capabilities-low) | Low | Deployment | Database container keeps its capabilities | Open |
| [D-07](#d-07--no-process-limit-low) | Low | Deployment | No process limit | Open |
| [D-08](#d-08--backups-are-complete-and-unencrypted-on-one-machine-low) | Low | Deployment | Backups unencrypted, on one machine | Open |
| [D-09](#d-09--packages-the-application-never-uses-medium-for-a-scan) | Medium (scan) | Deployment | Packages and files the application never uses | **Fixed in 1.0.2** |
| [D-10](#d-10--setuid-and-setgid-files-low) | Low | Deployment | setuid / setgid files (CIS 4.8) | **Fixed in 1.0.2** |

**Status on 2026-11-17.** `logsheet-backend:1.0.2` and `mfdcs-pwa:1.0.2` are built and verified
([deployment.md](deployment.md), "Again on 2026-11-16/17"; the PWA's `docker/DEPLOY.md`). They close
D-03, D-05, D-09, D-10 here and D-01, D-02, D-03, D-06, D-08 in the PWA's file, and mitigate B-06.
**No application code changed**: the jar inside 1.0.2 is the one 1.0.1 was built from, so every
B-entry above that is a code defect is still open in 1.0.2. The per-CVE answer to the security
unit's scans is [cve-report-1.0.2.md](cve-report-1.0.2.md) (Persian).

Suggested order: **B-01, B-02, B-03** (with P-01, P-02, P-03 — they are one change across both
repositories), then **B-04, B-05, B-09**, then the deployment group **B-06, B-07, B-08**.

---

## B-01

**A round completed after its deadline is refused and not kept.**

*Where.* [LogSheetService.java:252](../src/main/java/com/hnp/backendofflinefirst/service/LogSheetService.java) –269
(`submitOne`) and :1079 (`resolveFailedCompletion`'s fall-through).

*What.* Both return outcome `EXPIRED` and store nothing. Every other refusal on this path —
somebody else completed it, it was reassigned, it was cancelled — goes through `voidSubmission`
and keeps the payload in `log_sheet_void_submissions`.

*Why it matters.* [log-sheets.md §6](log-sheets.md) says the opposite, twice: the table lists
"Finished after `due_at` → Refused → **void submission**", and the next section is headed "The
payload is never dropped … or the work genuinely finished late". The document is what a
supervisor is told; the code does something else. Combined with P-02 (the device does not even
send such a round, and purges it after seven days) the work exists nowhere.

*Fix.*
1. In both places call `voidSubmission(sheet, dto, currentUserId, completedAt, now,
   "This log sheet completion deadline has passed.", "EXPIRED")` instead of building the result
   by hand. The outcome the device sees is unchanged (`EXPIRED`); what changes is that a row
   exists for the supervisor to review, and `extend` + un-void remains the way to make it stand.
2. Keep the `EXPIRE` action row exactly as it is; `voidSubmission` adds its `SUPERSEDE` row.
3. This makes B-04 more important, not less: a void must only be recorded for somebody with a
   claim on the sheet. Land the two together.
4. Add an additive field `kept: true` to `LogSheetSubmitResult` whenever a void row was written,
   so the device can tell "refused and kept" from "refused" (P-02 depends on it).

*Verify.* An integration test beside the existing void cases: submit with
`completedAt > dueAt` as the assignee → outcome `EXPIRED`, one void row, payload and
`sheet_form_data` intact, attachment cleanup protecting the ids it names.

*Fixed in V25 (2026-11-17).* Both places go through `voidSubmission(..., "EXPIRED")`; every
result that wrote a void row carries `kept: true` (`LogSheetSubmitResult.kept`, additive — and
`ApiResponseSupport.localize` carries it across, which the first attempt did not). Landed with
B-04 §1. `extend` re-opens the round and the tablet re-queues the completion with a new key, which
is then accepted on its own `completed_at`. Tests: `LateWorkKeptIntegrationTest` (late completion
kept; already-expired sheet; on-time completion still accepted; extension lets it through).

*Docs.* `log-sheets.md` §6 becomes true rather than needing an edit; add the `kept` field to
§7 and to the PWA's `docs/sync.md`.

## B-02

**Readings taken before the deadline but delivered after it are never accepted.**

*Where.* `refuseProgressIfNotOpenForThisActor`,
[LogSheetService.java:757](../src/main/java/com/hnp/backendofflinefirst/service/LogSheetService.java);
`markProgressIfStillOwned` (`s.dueAt >= :now`) in `LogSheetRepository`.

*What.* A progress push is judged on the **server** clock, deliberately — a live report cannot
belong to an earlier moment. The consequence was not followed through: an operator who walked
thirty of forty assets offline and reconnects ten minutes after the deadline has no route left.
Progress is refused (`EXPIRED`), a completion is impossible because the round is not complete,
and the device deletes the draft 24 hours after `dueAt` (P-01). The code comment at :759 says
"Neither a void submission nor a data loss: the round can still be completed afterwards" — true
only for a round that was already finished.

*Fix.* Keep refusing to *apply* late progress, and start *keeping* it:

1. In the `EXPIRED` branch of `saveProgressOne`, when the caller is the sheet's assignee, write
   one void-style row: `log_sheet_void_submissions` with `reason = "Arrived after the deadline
   (unfinished round)."`, the entries payload and `sheet_form_data`. **One row per (sheet,
   user)** — update it in place on a re-send rather than inserting, because progress is re-sent
   by design and has no `clientActionId`.
2. Answer `EXPIRED` with `kept: true` and `savedAt`. The device stops pushing and may purge.
3. The review page already renders a void's payload; label this reason distinctly so a
   supervisor can decide to `extend`.
4. No migration is needed for (1)–(3). If "one row per (sheet, user)" should be a database
   guarantee rather than a service rule, that is a partial unique index and is `V24`.

*Verify.* Push progress with `dueAt < now` → `EXPIRED`, `kept`, one row; push again with one
more asset → still one row, payload replaced; a non-assignee → no row (B-04).

*Fixed in V25 (2026-11-17)* — `keepLateProgress`. Two departures from the proposal, both on
purpose: the row is marked by a column (`late_progress`) rather than by its reason text, and the
one-row rule **is** a database guarantee (partial unique index `ux_lsvs_late_progress_per_user`);
and a re-send is **merged by asset** rather than replacing the payload, because the device sends
only what changed since its last push and a replacement would drop the earlier assets. An asset
the round does not have is refused `ERROR`. Verified live on 2026-11-17: two rounds answered
offline, reconnected after the deadline, one kept row each; after `extend` the same readings
arrived as ordinary progress and were applied; after a reassignment the second operator
completed the round and the first operator's readings stayed in their row. Tests:
`LateWorkKeptIntegrationTest`.

*Docs.* `log-sheets.md` §6 and the progress section; PWA `docs/sync.md`.

## B-03

**A replayed, already-voided submission is answered `DUPLICATE`.**

*Where.* [LogSheetService.java:214](../src/main/java/com/hnp/backendofflinefirst/service/LogSheetService.java)
(`actionLogger.isReplay`), :1120 (`voidSubmission` records the `clientActionId` on its
`SUPERSEDE` row), `LogSheetActionLogger.isReplay`.

*What.* `isReplay` asks only whether the id exists. A void records it. So: the first attempt is
voided, its response is lost on a weak link, the retry carries the same id and is told
`DUPLICATE` — which the device treats as delivered (PWA `sync/index.ts:226`): `synced`,
`serverStatus = SUBMITTED`, markers cleared, purged a day later. The operator is never told the
round was refused. The data is not lost — the void row exists — but nobody who did the work
knows to ask.

*Fix.* Make the replay answer what the original answered.

```java
// LogSheetActionLogger
public Optional<LogSheetActionType> replayedAs(String clientActionId) { … findFirstByClientActionId … }

// submitOne
Optional<LogSheetActionType> replay = actionLogger.replayedAs(dto.getClientActionId());
if (replay.isPresent()) {
    return replay.get() == LogSheetActionType.SUPERSEDE
        ? new LogSheetSubmitResult(dto.getLocalId(), serverId, storedReason, "SUPERSEDED")
        : new LogSheetSubmitResult(dto.getLocalId(), serverId, null, "DUPLICATE");
}
```

`storedReason` is the void row's own `reason` (look it up by sheet and submitter), so a cancelled
round still reads as cancelled. Also scope the lookup to the caller: an id presented by a
different user than the one who recorded it is not a replay.

*Verify.* Submit as a non-assignee twice with one `clientActionId` → `SUPERSEDED` both times,
one void row.

*Fixed in V25 (2026-11-17)* — `LogSheetActionLogger.replayedAs`, scoped to the caller. One
departure: a replay of a `SUPERSEDE` is not answered from a stored reason but **judged again**
from the sheet's current state, with `voidSubmission` recognising the key and writing nothing
twice — so the answer is always true *now*, and a completion reached that way does not record the
key a second time (`client_action_id` is unique). Tests: `LateWorkKeptIntegrationTest` (replays of
a kept late round, of a superseded round, and of a delivered one).

## B-04

**`submitOne` has no access check: any operator can write void rows and evidence files onto any
sheet in the plant.**

*Where.* [LogSheetService.java:213](../src/main/java/com/hnp/backendofflinefirst/service/LogSheetService.java) –250
(no `canView`), :1102 (`voidSubmission` stores the payload "exactly as it arrived");
[AttachmentService.java:187](../src/main/java/com/hnp/backendofflinefirst/service/AttachmentService.java) –214
(the V15 evidence exception skips `enforceCount`).

*What.* Holding `POST:/api/log-sheets/batch` — every operator does — is enough to name any
`serverId`. For a sheet the caller does not hold, in any unit:

- the answer distinguishes "not found", "already completed", "no longer assigned", "expired":
  an oracle for which sheet ids exist and what state they are in;
- a void row is written with an arbitrary, unvalidated payload, bounded only by the 64 MB JSON
  ceiling and 500 items a batch, with no limit on how many. It appears on that sheet's review
  page, in front of another unit's supervisor;
- the void then *authorises uploads*: `voidNaming` accepts any file whose id the caller's own
  void names, and `evidenceFor != null` skips the per-field ceiling. The caller chooses how many
  ids the void names. Up to 25 MB each, onto the attachment volume.

An authenticated insider can therefore fill the database and the disk, and put content in front
of supervisors of units they do not belong to. Nothing here reads another unit's data.

*Fix.*
1. **A claim on the sheet, or nothing.** Before any `voidSubmission`, require
   `logSheetAccessService.canView(sheet)` **or** that the caller was at some point this sheet's
   assignee (one indexed query on `log_sheet_action_log`: `CLAIM` / `ASSIGN` / `REASSIGN` /
   `TAKEOVER` naming them). Otherwise answer exactly as for a missing sheet
   (`"Log sheet not found on server."`, `ERROR`) and write nothing. The second half matters: an
   operator moved to another unit while offline is the case voids exist for.
2. **Bound the rows.** At most a handful of void rows per (sheet, user) — re-sending identical
   content updates `synced_at` on the existing row instead of inserting. A SHA-256 of the
   canonical payload in a new column (`V24`) makes "identical" cheap and exact.
3. **Bound the payload.** Refuse a single item whose entries exceed the sheet's own asset count
   (the `foreignAssetMessage` check already knows the list) before storing it.
4. **Bound the evidence.** Apply the ordinary per-field limits *within* a void:
   `enforceCount` over rows with the same `void_submission_id`, same field, same kind.

*Verify.* An operator of unit A submitting for a sheet of unit B they never held → `ERROR`, no
void row, no action row; the existing cases in `AttachmentApiIntegrationTest` § void evidence
still pass; an eleventh photo for a three-photo field of a void → 409.

*§1 fixed in V25 (2026-11-17)* — `hasClaimOn` in `voidSubmission`, with
`LogSheetActionLogRepository.everHeldBy`. Tests: `LateWorkKeptIntegrationTest` (a stranger leaves
nothing; an operator who held the sheet and was moved to another unit is still kept);
`AttachmentApiIntegrationTest` passes unchanged.

*§2–§4 fixed in V26 (2026-11-18).* §2: `payload_hash` (SHA-256 of the canonical content,
`VoidPayloadFingerprint`) — the same content again moves `synced_at` on the existing row; past
`MAX_KEPT_SUBMISSIONS_PER_PERSON` (5) different ones per person per sheet the next is answered with
its outcome and **`kept: false`**, stored nowhere. `false` is said rather than left out because
`SUPERSEDED`/`CANCELLED` imply kept, and the PWA would otherwise count its only copy as delivered
(and, with the purge switch on, could delete it — P-05). §3: `foreignAssetMessage` is checked in
`voidSubmission` itself, so a payload naming a stranger asset is refused on every path. §4:
`AttachmentService.enforceEvidenceCount` — the ordinary per-field ceiling within one void's
evidence, as a 400 (the void's list never changes; the device parks rather than retries). Tests:
`LateWorkKeptIntegrationTest` (`theSameRefusedContentUnderANewKeyIsKeptOnce`,
`differentContentIsKeptAsItsOwnRow`, `pastAHandfulOfDifferentRefusalsTheNextIsAnsweredButNotKept`,
`theCapIsPerPersonSoAColleaguesWorkIsStillKept`, `aRefusedRoundNamingEquipmentItDoesNotHaveIsNotKept`),
`AttachmentApiIntegrationTest.aVoidCannotDeliverMoreEvidenceThanTheFieldAllows`. The issue's
"eleventh photo → 409" became a 400 for the reason above.

*Docs.* `security.md` §6 ("Seeing a log sheet and changing it"), `log-sheets.md` §6.

## B-05

**Attachment files are deleted before the transaction commits.**

*Where.* [AttachmentService.java:597](../src/main/java/com/hnp/backendofflinefirst/service/AttachmentService.java) –598
(`delete`), :437–438 (`enforceCount` reclaiming a leftover), and `deleteFromWebFill`, which calls
`delete` and then does more work in the same transaction.

*What.* `attachmentRepository.delete(row)` is transactional; `storageService.delete(key)` is
not. If the transaction rolls back afterwards — a failed commit, a later exception — the row
comes back and the file does not, and every reader gets "Attachment file is missing from
storage".

Two reachable orders:
- `upload` → `enforceCount` reclaims a leftover (file gone) → MIME detection refuses the new
  file (`IllegalArgumentException`) → rollback. The reclaim runs *before* the upload is known to
  be acceptable. A "leftover" is by the method's own javadoc sometimes a sibling whose reference
  has not been pushed yet, i.e. a real photograph.
- `deleteFromWebFill` → `delete` (file gone) → `adoptIntoFieldReference` throws → rollback.

*Fix.*
1. Delete files **after commit**:
   ```java
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
       @Override public void afterCommit() { storageService.delete(key); }
   });
   ```
   wrapped in one helper (`deleteFileAfterCommit(key)`) used by all three call sites. A crash
   between commit and delete leaves an orphan file, which `AttachmentSweepService` already
   collects — the safe direction.
2. In `upload`, move `detectMimeType` / `matchesKind` **above** `enforceCount`, so nothing is
   reclaimed for a file that is about to be refused.
3. Symmetrically, a file written by `store` in a transaction that then rolls back is already an
   orphan the sweep handles; nothing to change there.

*Verify.* A test that uploads an unsupported type onto a full field and asserts the leftover's
file still exists.

*Fixed (2026-11-17), no migration.* `AttachmentService.deleteFileAfterCommit` is used by
`delete` (and so by `deleteFromWebFill`) and by `enforceCount`'s reclaim; outside a transaction
it deletes at once, as before. `upload` checks the type before `enforceCount`. Tests
(`AttachmentApiIntegrationTest`): `aRefusedUploadOntoAFullFieldReclaimsNothing`,
`aDeleteThatRollsBackKeepsTheFile`, and `deleteRemovesBothTheRowAndTheFile` now commits its test
transaction to see the file go — a test that runs inside a never-committed transaction will
never see a deferred delete, which is the point of the fix, not a defect in it.

## B-06

**Login lockout is a denial of service, and discloses real usernames; nothing is rate limited.**

*Where.* `security/LoginAttemptService.java` (keyed by username, in memory),
[AppAuthenticationProvider.java:106](../src/main/java/com/hnp/backendofflinefirst/security/AppAuthenticationProvider.java),
`roadmap.md` §2 (rate limiting, not built).

*What.*
- Five wrong passwords lock a username for fifteen minutes, for the tablet API and the panel
  alike. Five requests every fifteen minutes keep any account locked indefinitely. The bootstrap
  administrator is called `admin`; if that is the only administrator, nobody can reach the
  unlock page, and the way out is a restart.
- Only usernames that exist are ever counted, and a locked one is told so. So six requests
  distinguish a real username from an invented one — the oracle the dummy-hash equaliser was
  written to close, reopened by the lock message.
- Nothing bounds how often any of this may be asked.

*Fix.* Three layers, cheapest first.
1. **nginx** (PWA repository, D-01 in its `docs/issue.md`): `limit_req` on `/api/auth/login`,
   `/api/auth/challenge` and the two enrolment endpoints. This is the only layer that bounds
   volume.
2. **Count unknown usernames too**, in a bounded map (an LRU of a few thousand entries, so the
   map cannot be grown without limit), and answer them `Locked` on the same schedule. The two
   cases then behave identically from outside.
3. **Lock the pair, not the name**: key on (username, client address) so one address cannot lock
   an account for everybody else, with a much higher per-username ceiling (say 50 in the window)
   as the backstop against a distributed guess and against tripping Active Directory's own
   lockout — which is what this class exists to prevent. Needs B-08's forwarded-address
   handling to be correct first.
4. Operationally: rename the bootstrap account, and keep two active administrators.

*Docs.* `security.md` §9 (login throttle, login timing), `roadmap.md` §2.

## B-07

**Shipped defaults only warn.**

> **Decision, 2026-11-17: not changed in code.** The owner chose to keep the warning and make
> these a deployer's step rather than refuse to start. The checklist is in
> [deployment.md](deployment.md) «The settings that must not stay as they ship». The *Fix* below is
> kept as the design if this is ever reopened.

*Where.* [application.properties](../src/main/resources/application.properties) :5 (database
password), :80 (`app.cors.allowed-origins=*`), :88 (JWT secret), :126
(`app.auth.ldap.trust-self-signed=true`); `config/AdminBootstrapRunner.java:40` (`admin123`);
`config/ProductionReadinessRunner.java` ("Why it warns rather than refuses").

*What.* An installation that sets none of these boots, works, and logs warnings. The JWT default
is the least dangerous of them since authorities stopped living in the token (security.md §8).
The LDAP one is the most: `trust-self-signed=true` also switches the hostname check off, so
anything on the path between this server and the domain controller can present any certificate
and read every Active Directory password typed into the login form.

`deploy/app/app.env.example` already does the right thing for Docker — it sets the JWT secret
and the database password to *empty*, and an empty JWT secret refuses to boot. That protection
is one deleted line away from the default.

*Fix.*
1. Add a `prod` profile and set `SPRING_PROFILES_ACTIVE=prod` in `deploy/Dockerfile` (`ENV`) and
   in the systemd / WinSW units. Under it `ProductionReadinessRunner` **refuses to start** on:
   the shipped JWT secret, the shipped database password, CORS `*`, LDAP enabled with
   `trust-self-signed=true`, and an `admin` account whose hash still matches `admin123`.
   Development is unaffected because it does not set the profile.
2. Flip the default of `app.auth.ldap.trust-self-signed` to `false`. A site with a self-signed
   controller sets the pinned truststore the properties file already documents.
3. Have `AdminBootstrapRunner` take the first password from `APP_BOOTSTRAP_ADMIN_PASSWORD` when
   present, and force a change at first login when it is not.

*Docs.* `deployment.md` (secrets), `deploy/README.md`, `security.md` §9.

## B-08

**The panel is plain HTTP on 8081; no forwarded-header handling, no `Secure` cookie.**

*Where.* `application.properties` :74–75, :83–85 (no `server.forward-headers-strategy`, no
`server.servlet.session.cookie.*`); `controller/AuthApiController.java:117` (`clientIp` reads
`X-Forwarded-For` by hand); the PWA's nginx template (proxies `/api/` only); `roadmap.md` §17.

*What.* The tablets reach `/api/**` through nginx and TLS. The panel is not proxied, so an
administrator either works on the server itself or reaches `http://host:8081` across the LAN —
and then the login form posts a password (an Active Directory password, for AD accounts) and
carries the session cookie in clear text. Separately, `clientIp` trusts the first
`X-Forwarded-For` entry from *whoever connects*; that is correct only because the shipped nginx
overwrites the header and the compose file binds 8081 to loopback. Publish 8081 on a LAN address
(`APP_BIND_IP`) and any client chooses the address recorded on its session and its enrolment
request.

*Fix.*
1. Proxy the panel through nginx on its own server name (the shape `roadmap.md` §17 leaves
   open), and firewall 8081 to the nginx host.
2. `server.forward-headers-strategy=native` and
   `server.tomcat.remoteip.internal-proxies=<the nginx address>`; then replace `clientIp` with
   `request.getRemoteAddr()`. Tomcat's valve only honours the header from the listed proxy.
3. `server.servlet.session.cookie.secure=true`, `…same-site=lax`, and
   `…http-only=true` stated rather than assumed — under the `prod` profile of B-07 so local
   development over HTTP keeps working.
4. Read `roadmap.md` §18n (the `/error` dispatch re-entering the web chain) before doing this:
   it is the same area.

## B-09

**NFC fault reports are one transaction per batch.**

*Where.* [NfcFaultReportService.java:58](../src/main/java/com/hnp/backendofflinefirst/service/NfcFaultReportService.java) –59.

*What.* `submitBatch` is `@Transactional` around the loop — the shape finding 18e removed from
`LogSheetService.submitBatch`. Any item that throws (a `\u0000` in a reason, which PostgreSQL
refuses; two devices racing one `clientActionId` onto the unique index) rolls the batch back and
answers 500. The PWA retries a 5xx on every pass, with the same batch, for the life of the
tablet. A fault report is what unlocks manual entry for a broken tag, so the cost is an operator
who cannot record an asset.

*Fix.* Copy the log-sheet pattern exactly: remove `@Transactional` from `submitBatch`, run each
item through a `TransactionTemplate` (`PROPAGATION_REQUIRED`), catch `RuntimeException` per item
and answer `ERROR` for that item alone. Reject control characters in `normalizeReason`.

*Fixed (2026-11-18), no migration.* Exactly that, with one change: control characters other than
line breaks and tabs are **dropped**, not refused — a reason is free text an operator typed, and
refusing it would keep the report (and the manual entry it unlocks) off the server for a character
nobody can see. The per-item `ERROR` reads «این گزارش خرابی NFC روی سرور ثبت نشد؛ بقیه‌ی گزارش‌ها
ثبت شدند.». Tests: `NfcFaultReportServiceTest.oneReportThatThrowsDoesNotTakeTheRestOfTheBatchWithIt`,
`aNulInTheReasonIsDroppedNotStoredOrRefused`.

## B-10

**An attachment id can be taken by a colleague before its bytes arrive.**

*Where.* [AttachmentService.java:163](../src/main/java/com/hnp/backendofflinefirst/service/AttachmentService.java) –182.
*Read from code; not reproduced.*

*What.* The id is minted on the device and written into `form_data` before the file is
uploaded, and the progress push runs earlier in a sync pass than the attachment queue. Anyone in
the unit can read that `form_data` (`GET /api/log-sheets/{id}/entries`). They can then upload
their own file **with that id** to a sheet of their own. When the owner's upload arrives the
idempotent branch finds a row created by somebody else on another sheet and answers 403 — which
the device treats as retryable, so it retries every pass and the photograph never lands. The
panel lists a sheet's own rows, so the other file is not shown in its place; the evidence is
simply absent.

*Fix.* On the idempotent path, compare the stored row's `(log_sheet_id, asset_id, field_key)`
with the request's. A mismatch from a caller who may write the *requested* sheet is answered
`409` with a distinct, stable message (`ATTACHMENT_ID_TAKEN`), and the device re-mints the id
and rewrites its reference (P-06). The squatting row is then harmless.

*Fixed (2026-11-18), no migration.* `AttachmentService.answerRetry`: a stored row whose
`(log_sheet_id, asset_id, field_key)` differs from the request's is answered
`AttachmentIdTakenException` → `409` with `{"message": …, "code": "ATTACHMENT_ID_TAKEN"}` —
`ApiErrorResponse` gained an optional `code`, omitted for every other error. Only a caller who may
add a file where they asked gets it (checked by `authoriseNewFile`, the same rule as a new upload,
including a void's evidence); anybody else gets that upload's own 403. A mismatch is answered even
to the row's own uploader: returning the row would tell the device its file sits where its
reference points when it does not. Tests (`AttachmentApiIntegrationTest`, «Issue B-10»): another
sheet, another field, the round's own question, a stranger, and a void's evidence. The device half
is the PWA's P-06.

## B-11

**`returnTo` is an open redirect on four handlers; raw exception text reaches the page.**

*Where.* `web/AssetStatusRequestWebController.java:124`, `:147`;
`web/NfcFaultReportWebController.java:121`, `:130`. `AlarmWebController.safeReturn` (:149) is the
correct version of the same thing.

*What.* The parameter is concatenated onto `redirect:`. All four are CSRF-protected POSTs, so a
cross-site attacker cannot drive them — hence Low — but the rule exists in one controller and
not in the two beside it. The same handlers put `e.getMessage()` of any `RuntimeException` into
a flash message, which for a constraint violation is driver text.

*Fix.* Move `safeReturn` to `ui/WebRedirectSupport` and use it in all five places; pass
exception messages through `ErrorTranslator.toFa`, which already falls back to a generic
sentence.

*Fixed (2026-11-18).* `WebRedirectSupport.safeReturn` / `redirectTo` (one leading `/`, no `\`, no
`://`), used by `AssetStatusRequestWebController`, `NfcFaultReportWebController` and
`AlarmWebController`; the four flash messages go through `ErrorTranslator.toFa`. Test:
`WebRedirectSupportTest` (`aReturnToThatCouldLeaveTheApplicationFallsBack` — absolute and
protocol-relative URLs, backslash tricks, `javascript:`, a relative path, blank and null).

## B-12

**Device enrolment: status disclosed by id at login; the pending cap can be filled.**

*Where.* [DeviceEnrollmentService.java:291](../src/main/java/com/hnp/backendofflinefirst/service/DeviceEnrollmentService.java) –298
(`authorizeLogin`), :81–82 (`MAX_LIVE_PENDING`).

*What.* `issueChallenge` answers about a device only to a caller presenting both its id and its
key fingerprint, "so ids cannot be walked". `authorizeLogin` answers `DEVICE_UNKNOWN`,
`DEVICE_REVOKED` or `DEVICE_NOT_APPROVED` on the id alone. And fifty live pending requests in
total is a ceiling ten addresses can hold full — five each, each needing only a fresh P-256
public key — which stops every real tablet enrolling for thirty minutes at a time.

Only relevant while enrolment is on.

*Fix.* Send the fingerprint with the login and collapse every pre-signature refusal into one
answer when it does not match; rate-limit the enrolment endpoint at nginx; give the approver a
"reject all pending" action so a flood is one click to clear.

*Fixed (V27, 2026-11-18).* The first half without a wire change: `authorizeLogin` now spends the
nonce **before** it reads the device. A nonce is only ever issued by `issueChallenge` to a caller
presenting the key's fingerprint, so a live one is already that proof; without it every id is the
same `CHALLENGE_INVALID`. A real tablet is unaffected (it learns its state from the challenge, and a
change since is still reported because its nonce is live), and no PWA build had to change. The
nginx limit is the shipped compose configuration's (D-01 in the PWA's file). «رد همه» on `/devices`
is `POST /devices/pending/reject`, permission `POST:/devices/pending/reject` (V27, derived from
`POST:/devices/{id}/revoke`), each request rejected through `revoke` exactly as one click would.
Tests: `DeviceEnrollmentIntegrationTest.aLoginSaysNothingAboutADeviceWithoutALiveNonce`,
`aDeviceRevokedAfterItsNonceWasIssuedIsToldSo`; `DeviceAdminPanelIntegrationTest`
(`rejectingAllPendingTurnsDownEveryWaitingRequestAndNothingElse`, the button's own permission).
Port 8081 being reachable on the network is B-08's, and deliberately left for later.

## B-13

**`expireOverdueSheets` wraps its whole loop in one transaction.**

*Where.* `service/LogSheetScheduler.java:114`–115.

*What.* `tryExpireOverdue` is itself `@Transactional` and joins the outer one, so one sheet that
throws rolls back every expiry of that tick, and of every later tick while it keeps throwing.

*Fix.* Remove `@Transactional` from the scheduler method and wrap each call in try/catch with a
WARN naming the sheet.

*Fixed (2026-11-22, no migration).* As proposed: `expireOverdueSheets` has no transaction of its
own, so each `tryExpireOverdue` commits or rolls back alone, and a sheet that throws is logged at
WARN with its id and `dueAt`, stays open and is retried on the next tick. `jobs.md` «Log sheet
expiry» says so. Tests: `LogSheetSchedulerExpiryTest` (`aSheetThatThrowsDoesNotStopTheOthers`,
and `theTickHasNoTransactionOfItsOwn` — the catch alone would not be the fix, because a joined
inner transaction marks the outer one rollback-only even when its exception is caught).

## B-14

**Every `@Scheduled` job shares one thread.**

*Where.* No `spring.task.scheduling.pool.size`; `AttachmentSweepService.scheduledSweep` (:167)
and `ApiKeyUsageRetentionService.scheduledPurge` run their work inline. (`AuditRetentionService`
was listed here too; re-read on 2026-11-19, it is **not** affected — its `scheduledPurge` only
starts the work on its own `purgeExecutor`.)

*What.* The alarm jobs are written to "only nudge" for exactly this reason. The 02:00 sweep is
not: it walks the entire attachment root, and while it does, no log sheet is generated and none
expires. Harmless at today's volume; it grows with every photograph ever taken.

*Fix — two options were proposed; option 2 is the one to take* (decided on re-reading,
2026-11-19).

1. ~~`spring.task.scheduling.pool.size=3`~~ — **not recommended.** Each method still never
   overlaps *itself*, but generation, expiry, the import watchdog and the nightly jobs could then
   overlap *each other*, and [jobs.md](jobs.md) states the opposite as a guarantee («no scheduled
   job can ever overlap another») — it is the stated reason the generation tick has no lock
   inside one instance. Taking this option means re-checking every pair of jobs against that
   assumption and rewriting it, for no gain over option 2.
2. **Hand only the long work to its own executor; keep the scheduler thread single.** The short
   ticks stay where they are and the guarantee in `jobs.md` stays true. It is cheaper than it
   sounds, because most of it exists:
   - **Audit purge** — already done (above). Nothing to change.
   - **Attachment sweep** — `sweepExecutor` already exists and the panel's manual run uses it;
     only `scheduledSweep` bypasses it and calls `runSweep()` inline. Route the scheduled run
     through the same `synchronized` start path. This also closes a race found while reading it:
     `scheduledSweep` is neither `synchronized` nor goes through `startSweep`, so a manual start at
     the same moment as the 02:00 run can see `isRunning() == false` twice and run **two sweeps
     at once** over the same root. Add a `@PreDestroy` `shutdownNow()` as `AuditRetentionService`
     has.
   - **Integration usage purge** — the only one that needs a new executor: one daemon thread, a
     running flag so a run is skipped rather than queued while one is in progress, `@PreDestroy`.
     `purge(now)` stays synchronous (the integration test calls it directly); only
     `scheduledPurge` submits it.

   *Costs and risks of option 2:*
   - **Exceptions disappear.** `ExecutorService.submit()` keeps a thrown exception inside the
     `Future` and logs nothing, where the scheduler thread used to log it. The submitted body must
     catch and `log.error` itself (`runSweep` already does; the new purge task must).
   - **Shutdown mid-run.** The threads are daemons and `shutdownNow` interrupts them. Both jobs
     tolerate it: the sweep deletes file by file, the purge commits batch by batch, and the next
     run continues.
   - **New concurrency, limited and already known.** The sweep and the purge would run beside the
     generation and expiry ticks. Neither touches `log_sheets`, and a manual sweep already runs at
     any hour beside them — the grace period exists for that case — so nothing new is exposed.
   - **Tests.** `AuditRetentionScheduleTest` and `ApiKeyUsageRetentionIntegrationTest` are
     unaffected (the first already runs against an executor, the second calls `purge` directly).
     The scheduled sweep path needs a test that waits for completion, and one for the race above.
   - **Docs.** `jobs.md`: the sentence that a long sweep holds the scheduler thread, and the
     «Pool» row of the integration usage purge (:341).

   About 2–3 hours with tests and docs — the same as option 1, without changing the guarantee.

*Fixed (2026-11-22, option 2, no migration).* The scheduler thread stays single and `jobs.md`'s
guarantee stands. `AttachmentSweepService.scheduledSweep` now goes through the same `synchronized`
start as the panel (`tryStartSweep`), so the walk runs on `sweepExecutor` and the
manual-versus-02:00 race is closed — check and claim are one locked step; a tick that finds a sweep
running logs and skips. `@PreDestroy shutdownNow()` added. `ApiKeyUsageRetentionService` gained a
one-thread daemon executor and an `AtomicBoolean`: a tick submits `purge(now)` and returns, a tick
during a run is skipped, the task logs its own failure at ERROR and always clears the flag;
`purge(now)` is unchanged and synchronous. Tests: `ScheduledLongJobsOffSchedulerThreadTest` (each
scheduled call returns while its work is held open on a latch, on the job's own named thread; a
manual start during the scheduled sweep is refused; a manual and a scheduled start released
together run one sweep; an overlapping purge tick is skipped; a failed purge does not block the
next) and `AttachmentSweepIntegrationTest.theScheduledSweepRunsInTheBackgroundAndCompletes`.
`AuditRetentionScheduleTest` and `ApiKeyUsageRetentionIntegrationTest` pass unchanged.

## B-15

**An attachment download buffers the whole file and cannot serve a range.**

*Where.* `controller/AttachmentController.java:67`; `AttachmentStorageService.read` (:87); the
panel's `GET /log-sheets/{id}/attachments/{attachmentId}`.

*What.* `Files.readAllBytes` into a `byte[]` response: 20 MB of heap per video per viewer, and a
browser cannot seek in a video it cannot request a range of.

*Fix.* Return a `FileSystemResource` (after `resolveWithinRoot`) as the body. Spring then
streams it and answers `Range` requests itself.

*Fixed (2026-11-22, no migration).* As proposed: `AttachmentStorageService.resource(key)` resolves
within the root and returns a `FileSystemResource`; `DownloadedAttachment` carries it instead of a
`byte[]`; `AttachmentController.download` and the panel's route return `ResponseEntity<Resource>`
with the same type, disposition and cache headers. A request without `Range` gets `200`, the whole
file and `Accept-Ranges: bytes`; with one, `206` and `Content-Range`. One thing the entry did not
name: `LoggingAspect` serialises a service's result at DEBUG, and a `Resource` inside a record would
have been read whole by Jackson — its private mapper now writes a `Resource` by class and file name
(AGENTS.md #234). Tests: `AttachmentApiIntegrationTest.answersARangeRequestWithPartialContent`,
`thePanelRouteServesTheFileAndARange`, `LoggingAspectTest.aFileResourceNestedInsideABusinessObjectIsNamedNotRead`.
**Not verified live**: neither a video seeking in a real browser on the panel nor the tablet's
download path (which fetches the whole file without `Range`, so it should see no change) was run
against a running server.

## B-16

**The JSON ceiling is 64 MB here and 32 MB at nginx.**

*Where.* `application.properties` :243 (`app.http.max-json-body-bytes=67108864`); the PWA's
`PWA_MAX_BODY_SIZE=32m`.

*What.* The comment at :231 explains that the limit is deliberately loose because a 413 would
wedge a long-offline tablet into retrying the same body forever. nginx answers that 413 first,
at half the size.

*Fix.* The real fix is client-side chunking (P-04). Until then make the two numbers agree
(`PWA_MAX_BODY_SIZE=64m`) and say in both files that they are a pair.

*Fixed (2026-11-22, configuration only).* The PWA's `PWA_MAX_BODY_SIZE` defaults to `64m`
(Dockerfile, compose, `.env.example`; its D-05), and both sides now say they are a pair —
`application.properties` above `app.http.max-json-body-bytes`, and the PWA's nginx template,
`.env.example` and `docker/DEPLOY.md`. The chunking the entry called the real fix exists since the
PWA's P-04 (8 MB a request). An installation whose own `.env` sets `32m` keeps it until changed.

## B-17

**Restated, not new: what the server takes on the device's word.**

- `completedAt` decides the deadline and is the device's clock (`integration-api.md` §12,
  PWA `roadmap.md` §7). Nothing checks that it is not in the future or implausibly old. *Cheap
  improvement:* refuse a `completedAt` more than a few minutes ahead of the server, and flag —
  not refuse — one more than N days before `synced_at`.
  **Done (V28, 2026-11-18), with one change from the proposal:** a time more than
  `app.sync.device-clock-future-tolerance-ms` (5 min) ahead is not *refused* but **replaced by the
  receipt time**, the claim kept in `completed_at_reported` — refusing it would answer a tablet
  holding the only copy of a round with an error it can never fix by resending. A round finished on
  time on a fast clock is now accepted. A round arriving 72 h or more after its completion says so on
  its page (display only, from `completed_at` and `synced_at`). And the device half: the PWA now
  stamps `completedAt` — and decides every deadline — on the server's clock as it measured it, and
  sends the correction as `clientClockOffsetMs` (stored as evidence). What is left: a clock set back
  after the last sync is still the device's word (bounded by the app's floor to the last server time
  it saw).
- `entrySource` (`PWA_NFC` / `PWA_QR` / `PWA_MANUAL`) is reported by the client. A hand-written
  request with a valid token can claim a scan that never happened. This is inherent to a web
  client (`roadmap.md` §15).
- `GET /api/asset-entries/nfc/{nfcTagId}` is plant-wide and returns the chip serial
  (`security.md` F3). If it is ever scoped, answer 404, not 403.

## B-18

**A minimal hand-written `.xlsx` fails the batch import but passes the direct one.**

*Where.* `util/ExcelUtils.countDataRows(Path)` (opens the stored upload with
`new XSSFWorkbook(File)`), then `ExcelImportService.importLocations` → `POIXMLException: Premature
end of file` (`ExcelImportService.java:77`, called from `ImportJobRunner.runAsync`).

*What.* Found while verifying 1.0.2, and **identical on 1.0.1** — not an image change. A workbook
with only the parts the format requires (no `styles.xml`, no `docProps`) imports correctly through
`POST /locations/import`, and the same bytes submitted to `/batch-import` end `FAILED` with zero
rows. A workbook as Excel writes it — the application's own template, filled in — passes both
paths (40 rows, Persian, verified). So an ordinary user is not affected; a file produced by a
script or another tool may be.

*Suspected cause — not confirmed.* `new XSSFWorkbook(File)` opens the package **read-write**, and
closing it lets POI write the package back to the stored file. For a minimal package that
rewrite plausibly leaves an empty part, which the second open then refuses. The direct path reads
a stream and never writes.

*Fix.* Open read-only in `countDataRows(Path)`:
`try (OPCPackage pkg = OPCPackage.open(path.toFile(), PackageAccess.READ); Workbook wb = new XSSFWorkbook(pkg))`.
Verify with an integration test that submits a minimal package to the batch path and asserts the
stored file's bytes are unchanged after counting.

*Fixed (2026-11-22, no migration).* The suspected cause is **confirmed**: a test that counts the
rows of a stored five-part package and then reads it as `ImportJobRunner` does failed with the same
`POIXMLException: Premature end of file`, and the stored bytes had changed. `countDataRows(Path)`
now opens the package `PackageAccess.READ`, exactly as proposed. Tests:
`ExcelUtilsMinimalPackageTest` (count, then re-read, then the bytes are unchanged) and
`BatchImportMinimalPackageIntegrationTest` (`ImportJobService.submit` with the minimal package, the
stored file unchanged, and the same file imports its two locations through `importEntity` — the
runner's own call); the latter was checked to fail against the old code. AGENTS.md #233.

---

# Container images

Three images are built from this repository's `deploy/`. Verdict first: **all three are built
with more care than most** — pinned bases, security upgrades at build, non-root numeric users,
an allow-list `.dockerignore`, no secret in any layer, and compose files that add a read-only
root, `cap_drop: ALL` and `no-new-privileges`. The entries below are what is left.

## What is already right

| | App (`deploy/Dockerfile`) | PostgreSQL (`deploy/postgres/image`) |
|---|---|---|
| Base | `eclipse-temurin:25.0.5_8-jre-resolute` — exact build, JRE not JDK | `postgres:18.7-trixie`, `pgdg` packages held so the upgrade cannot move the server |
| User | `10001:10001`, no shell, no home | `999:999`; `gosu` removed, so nothing can go back to root |
| Context | `.dockerignore` is `**` plus one jar — logs and photographs cannot enter a layer | one script |
| Secrets | none; everything arrives as environment at run time | none; the snake-oil key is deleted |
| Runtime | `read_only`, tmpfs `/tmp`, `cap_drop: ALL`, `no-new-privileges`, bound to `127.0.0.1` | `no-new-privileges`, bound to `127.0.0.1`, SCRAM, data checksums |
| Health | readiness, which includes the database | `pg_isready` |
| Failure mode | `ExitOnOutOfMemoryError` | refuses to initialise an empty cluster beside existing data |

## D-01 — the image boots with development defaults (Medium)

The jar carries `application.properties`, defaults included. Nothing in the image says "this is
production", so B-07 applies to it in full. **Fix:** `ENV SPRING_PROFILES_ACTIVE=prod` in the
final stage, with the fail-fast checks of B-07 behind that profile. **Not done, with B-07
(2026-11-17):** the values in `app.env` are the deployer's to change — `deployment.md` «The
settings that must not stay as they ship».

## D-02 — the database link is clear text when the two hosts differ (Medium)

`app.env.example` points at `jdbc:postgresql://10.0.0.11:5432/…` with no `sslmode`, and the
database image runs with `ssl = off` ([deployment.md](deployment.md)). On two hosts, every
reading, every name and the SCRAM exchange cross the plant LAN unencrypted, and the stock
`pg_hba.conf` accepts any address that can reach the port.

**Fix:** either `ssl = on` with a certificate from the plant CA and
`?sslmode=verify-full&sslrootcert=…` on the JDBC URL, or — at minimum — a host firewall rule
admitting 5432 from the application host only, plus a `pg_hba.conf` line naming that address
instead of `all`.

## D-03 — bases are pinned by tag, not by digest (Low)

`25.0.5_8-jre-resolute`, `18.7-trixie` are exact, but a tag can be re-pushed. **Fix:** append
`@sha256:…` to each `ARG`, recorded at the moment the image is pulled on the build machine. On a
site that loads images from a tarball this is mostly a statement of record; it costs one line.

**Fixed in 1.0.2 for the backend** (`eclipse-temurin:25.0.5_8-jre-resolute@sha256:480ef64e…`,
recorded 2026-11-16). A build machine that received the base by `docker load` has no registry
digest and must pass the tag alone — `deploy/README.md` says how. The PostgreSQL image was left as
it is, by decision.

## D-04 — `apt-get upgrade` makes a build unrepeatable (Low, accepted)

Deliberate and explained in the file. The consequence to record: two builds of one commit can
differ. **Fix:** tag with the build date as well as the version, keep the image tarball that was
deployed, and scan it (`trivy image --severity HIGH,CRITICAL`) on the build machine before it
leaves — the plant cannot reach an advisory database (security.md §6, "Dependencies").

## D-05 — `curl` is in the image only for the health check (Low)

A general-purpose download tool in a container whose root filesystem is otherwise read-only.
**Fix (optional):** replace the probe with a ten-line Java class in the jar
(`java -cp … HealthProbe`) and drop the package. Low value; listed so it is a decision.

**Fixed in 1.0.2**, more cheaply than proposed: `deploy/healthcheck.sh` makes the readiness
request through bash's `/dev/tcp` — no JVM start every 30 s, no package. The scan showed the real
cost of `curl` was not the tool but its 23 dependencies (GnuTLS, SASL, LDAP, Kerberos …), 13 of
the 34 findings in Rev-003. Verified: exit 0 healthy, 1 on the wrong port, 1 with the database
unreachable, 0 again two seconds after it returned.

## D-06 — the database container keeps its default capabilities (Low)

The app's compose file drops every capability; the database's does not. With `USER 999` and
`gosu` removed, PostgreSQL should need none. **Fix:** add `cap_drop: [ALL]` to
`deploy/postgres/docker-compose.yml` — **test it on a fresh volume and on an existing one
first**, since first initialisation is where a missing capability would show.

## D-07 — no process limit (Low)

Neither compose file sets `pids_limit`. **Fix:** `pids_limit: 512` on the app (Tomcat's 200
threads plus the pools, with room), `pids_limit: 256` on the database (`max_connections=100`).

## D-08 — backups are complete and unencrypted, on one machine (Low)

`logsheet-backup.sh` writes a `pg_dump` and a hard-linked copy of the attachments to a local
disk, mode 600. It holds everything this system knows, including personal data in lookup lists.
**Fix:** copy each run to a second machine, and encrypt the dump at rest (`age` or `gpg` with a
key that is not on the server). Restore from one at least once.

## D-09 — packages the application never uses (Medium, for a scan)

The security unit's Trivy scan of 1.0.1 (Rev-003) listed 34 operating-system findings and one in
`/usr/bin/pebble`. None was HIGH or CRITICAL and none had an Ubuntu fix, so `apt-get upgrade` could
not move any of them. What could: not shipping what the process never touches. The JVM maps one
operating-system library, `libc6`, and runs no other program.

**Fixed in 1.0.2.** `curl`, `p11-kit`, `fontconfig` and their orphaned dependencies purged
(126 → 92 packages); `/usr/bin/pebble` and the base image's unused entrypoint deleted. That
removes 14 of the 35 findings (13 + `pebble`). The other 21 are in packages the base system needs
and the process never runs — `util-linux`, `dash`, `tar`, `perl-base`, `rust-coreutils`,
`login`/`passwd`, `libsystemd0`/`libudev1`; each is argued in the CVE report. The removal that can
break something is `fontconfig` (AGENTS.md #219); every Excel export was verified byte-identical.

## D-10 — setuid and setgid files (Low)

CIS Docker 4.8. The backend image had eleven (`su`, `mount`, `passwd` …), the PostgreSQL image has
eleven, the PWA image none. **Fixed in 1.0.2 for the backend**: every bit cleared, and the build
fails if one is left. The PostgreSQL image is unchanged.

## What the security unit's findings were, per image

| Report | Image | Finding | Answer |
|---|---|---|---|
| CIS 6-3, 4.1 / 4.6 | the **official** PostgreSQL image | no `USER`, no `HEALTHCHECK` | True of the official image. The backend and PWA images have both (verified with `docker inspect`). The derived `logsheet-postgres` image would close it; left as is, by decision |
| CIS 6-3, 4.4 | — | images not rebuilt with patches | Backend and PWA rebuilt with `--no-cache` as 1.0.2. The official PostgreSQL image is the newest published (built 2026-11-12; the registry still serves that build on 2026-11-17) |
| S112 secret | PostgreSQL | `ssl-cert-snakeoil.key` | Generated when the official image was built, identical in every copy worldwide, unused (`ssl = off`). Not a secret of this site |
| 6-4 secrets | the PWA **host**, not an image | `mfdcs/ssl/key.pem`, `tempssl-gen/localCA.key`, `tempssl-gen/nginx.key` | The first is nginx's TLS key and must stay (owner uid 101, mode 0400). The other two must leave the server: the CA key is what every tablet trusts (PWA issue D-07) |

---

# Not reviewed

So that nobody reads silence as a clean bill:

- the panel's controllers and Thymeleaf templates, line by line (spot checks only: no
  `th:utext`, no dynamic SQL, CSRF enabled on the web chain);
- Excel import and export, the eight reports and the dashboard formulas;
- alarm evaluation and webhook delivery beyond the address policy;
- `LdapAuthenticationService` and the trust-store socket factories;
- `/integration/v1/**` beyond its filter chain;
- dependency advisories — run `mvn org.owasp:dependency-check-maven:check` on a machine with
  internet;
- anything that needs a running instance: performance numbers, the suite, a live probe.
