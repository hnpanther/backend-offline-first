# Background Jobs and Async Work

Everything in the application that runs without a user waiting for it: what it does, where the
code is, how it is configured, and how it fails.

There are four kinds, and the distinction matters when you are debugging:

| Kind | Trigger | Survives a restart? |
|---|---|---|
| **Scheduled** (`@Scheduled`) | a timer or cron, forever | yes — the timer restarts |
| **Startup** (`ApplicationRunner`) | once, at boot | n/a — it *is* the restart |
| **Async task** (`@Async`) | a user action, on a thread pool | **no** — in-flight work is lost |
| **Manual long-running** | an admin presses a button | no |

---

# 1. Scheduled jobs

## Log sheet generation

| | |
|---|---|
| **Code** | [`LogSheetScheduler.generateDueSheets()`](../src/main/java/com/hnp/backendofflinefirst/service/LogSheetScheduler.java) |
| **Trigger** | `@Scheduled(fixedDelayString = "${app.scheduler.log-sheet-gen-ms:60000}")` — every 60 s |
| **Does the work** | `LogSheetGenerationService.runScheduled(template, now, maxBackfill)` |

Finds every template that is due and generates its rounds.

```java
templateRepository.findByGenerationModeAndScheduleActiveTrueAndNextRunAtLessThanEqual(
        GenerationMode.SCHEDULED, now);
```

Three conditions must all hold: `generation_mode = SCHEDULED`, `schedule_active = true`, and
`next_run_at <= now`. A template with `active = false` is skipped inside the loop, so
deactivating a template stops generation without unsetting its schedule — the configuration
survives for when it is switched back on.

**`fixedDelay`, not `fixedRate`.** The delay is measured from the *end* of the previous run,
so a slow generation cycle cannot cause runs to pile up on top of each other.

### Backfill, and why it is capped

If the server was down for a week, a template on an hourly schedule is 168 runs behind.
Generating all of them would flood the operators' inbox with rounds nobody can now perform for
times that have passed.

`app.scheduler.log-sheet-max-backfill` caps how many missed runs are generated in one pass.

```properties
app.scheduler.log-sheet-max-backfill=${APP_SCHEDULER_LOG_SHEET_MAX_BACKFILL:0}
```

**The default is 0 — no backfill.** After an outage, generation resumes from now and the
missed windows stay missed, which is the honest outcome: nobody walked the plant during the
outage, and manufacturing sheets that claim otherwise would be worse than the gap. Raise it
only if you have a specific reason to want the empty sheets on record.

### Failure handling

Each template is generated inside its own `try`. One template with a broken scope logs an
error and the loop moves on — one bad configuration must not stop generation for the whole
plant.

```java
} catch (Exception e) {
    log.error("Scheduled generation failed for template {}: {}", template.getId(), e.getMessage(), e);
    businessEventLogger.error("SCHEDULER_GENERATE", "templateId=" + template.getId(), e);
}
```

## Log sheet expiry

| | |
|---|---|
| **Code** | [`LogSheetScheduler.expireOverdueSheets()`](../src/main/java/com/hnp/backendofflinefirst/service/LogSheetScheduler.java) |
| **Trigger** | `@Scheduled(fixedDelayString = "${app.scheduler.log-sheet-expiry-ms:60000}")` — every 60 s |

Closes rounds whose deadline has passed. It looks at `PENDING`, `ASSIGNED` and `IN_PROGRESS`
sheets with `due_at <= now`, and **branches on whether any data was entered**:

```java
if (sheet.getDraftSavedAt() != null) {
    logSheetService.finalizeDraftOnExpiry(sheet.getId(), now);   // → SUBMITTED
    continue;
}
logSheetService.tryExpireOverdue(sheet.getId(), now);            // → EXPIRED
```

**A draft is submitted, not discarded.** An operator who walked the plant, recorded readings
and ran out of time has produced real data. Throwing it away because a clock ran out would
destroy field measurements that can never be retaken.

**A sheet with no draft expires.** Nothing was recorded, so `EXPIRED` is the truth, and it is
what the compliance report counts as a missed round.

Note the consequence: an auto-finalised draft **raises asset status change requests** like any
other completion, and its `assignee_user_id` may be null for a pool sheet — which is how a
request with no actor gets created. See [schema.md](schema.md#asset_status_change_requests).

## Orphan attachment sweep

| | |
|---|---|
| **Code** | [`AttachmentSweepService.scheduledSweep()`](../src/main/java/com/hnp/backendofflinefirst/service/AttachmentSweepService.java) |
| **Trigger** | `@Scheduled(cron = "${app.attachments.sweep.cron:0 0 2 * * *}", zone = "${app.attachments.sweep.zone:Asia/Tehran}")` |
| **Admin UI** | Settings → attachment maintenance (run on demand, watch progress, cancel) |

Deletes attachment files on disk that no database row refers to.

```properties
app.attachments.sweep.enabled=${APP_ATTACHMENTS_SWEEP_ENABLED:true}
app.attachments.sweep.grace-hours=${APP_ATTACHMENTS_SWEEP_GRACE_HOURS:24}
app.attachments.sweep.cron=${APP_ATTACHMENTS_SWEEP_CRON:0 0 2 * * *}
app.attachments.sweep.zone=${APP_ATTACHMENTS_SWEEP_ZONE:Asia/Tehran}
```

### Why orphans exist at all

The row is the source of truth and the file is a satellite, so anything that removes a row
without going through `AttachmentService.delete` leaves the bytes behind:

- **A deleted log sheet.** `attachments.log_sheet_id` is `ON DELETE CASCADE`, so the rows
  vanish inside the database and nothing ever tells the filesystem. This is the common case.
- **A crash mid-upload.** The file is written before the transaction commits; if the process
  dies in between, the file exists and the row never will.
- **A database restore to an earlier point.** Rows go back in time; files do not.

None of these are bugs to prevent. They are the normal cost of keeping bytes out of the
database, and this job is the other half of that trade.

### The grace period is the entire safety design

A file younger than `grace-hours` is **never** deleted, even with no row. Between writing the
bytes and committing the transaction there is a window in which a perfectly good upload looks
exactly like an orphan. A sweep running in that window would delete a file an operator had
just captured, and the row would then point at nothing.

Twenty-four hours is enormously more than that window needs, which is the point: the cost of
waiting is some dead bytes for a day, and the cost of being wrong is lost evidence.

### The reverse case is reported, never repaired

A row whose file is missing is **counted and shown to the administrator, and left alone.**
Deleting those rows would erase the only remaining record that something was lost, exactly when
somebody most needs to know.

**A cron, not a fixed delay.** A fixed delay pins the sweep to whenever the server last
restarted, so a lunchtime deploy moves the nightly maintenance to lunchtime forever. The zone
is explicit because "midnight" is meaningless without it — a server running in UTC would sweep
at 03:30 Tehran time.

`isRunning()` is checked first, so a scheduled pass never collides with one an admin started
by hand.

---

# 2. Startup runners

These implement `ApplicationRunner` and run once, after the context is ready.

## Admin bootstrap

| | |
|---|---|
| **Code** | [`AdminBootstrapRunner`](../src/main/java/com/hnp/backendofflinefirst/config/AdminBootstrapRunner.java) |

Creates the `admin` / `admin123` account **only when no user holds the ADMIN role**. On a
populated database it does nothing.

`personnel_code` is `NOT NULL`, so the bootstrap account gets the deterministic placeholder
`ADMIN`, which the administrator replaces from the users page.

> **Change this password before the system leaves your desk.** It is a first-boot convenience,
> not a credential.

## Entry severity backfill

| | |
|---|---|
| **Code** | [`EntrySeverityBackfillRunner`](../src/main/java/com/hnp/backendofflinefirst/config/EntrySeverityBackfillRunner.java) |

Stamps `max_severity` on log sheet entries written before that column existed.

A NULL there means "never evaluated", which is invisible to the exception report — and that
reads as "nothing is wrong" rather than "this was never checked."

**Idempotent and self-disabling.** It selects only entries whose severity is still NULL *and*
that actually have values, so a second boot finds nothing:

```java
long pending = logSheetEntryRepository.countUnevaluatedWithValues();
if (pending == 0) return;
```

On a normal boot that is one indexed count. Safe to leave in place permanently — it is what
makes the column correct on any environment that gets it later.

It works in batches of 200 sheets per transaction rather than one enormous transaction, and it
evaluates each row against **that sheet's own `field_definitions_snapshot`**, so historic rows
are judged by the ranges that applied when they were recorded — the same rule the live write
path follows.

## Import job recovery

| | |
|---|---|
| **Code** | [`ImportJobRecoveryRunner`](../src/main/java/com/hnp/backendofflinefirst/config/ImportJobRecoveryRunner.java) → `ImportJobService.recoverStaleRunningJobs()` |

Cleans up import jobs that a shutdown interrupted.

- **`RUNNING`** → `FAILED` with "Import interrupted by server restart." The thread that was
  processing it is gone; nothing will ever finish it.
- **`PENDING` with its file still on disk** → re-queued.
- **`PENDING` with the file gone** → `FAILED` with "Import file missing after server restart."

> **This is currently the *only* way a stuck import job is ever cleared** — which is why a
> wedged import means a restart. See the limitation noted under Excel import jobs below.

---

# 3. Async task pools

Configured in [`AsyncConfig`](../src/main/java/com/hnp/backendofflinefirst/config/AsyncConfig.java).

| Bean | Core | Max | Queue | Used by |
|---|---|---|---|---|
| `auditExecutor` | 2 | 4 | 256 | `AuditWriteService.save` — every audit row |
| `importExecutor` | 1 | 1 | 16 | `ImportJobRunner.runAsync` — one import at a time |

Both wrap tasks with `AsyncConfig::wrapWithMdc`, copying the request's MDC (correlation id,
username) onto the worker thread so a background log line can still be traced back to the
request that caused it.

## Audit writes

| | |
|---|---|
| **Code** | [`AuditWriteService`](../src/main/java/com/hnp/backendofflinefirst/service/AuditWriteService.java), driven by [`RepositoryAuditAspect`](../src/main/java/com/hnp/backendofflinefirst/aspect/RepositoryAuditAspect.java) |
| **Pool** | `auditExecutor` — 2–4 threads, queue 256 |
| **Config** | `app.audit.enabled`, `app.audit.async.core-pool-size`, `app.audit.async.max-pool-size` |

An aspect intercepts **every** `CrudRepository.save`, `saveAndFlush`, `delete` and `deleteById`,
diffs the entity, and hands the row to the pool:

```java
@Around("execution(* org.springframework.data.repository.CrudRepository+.save(..))")
```

Nothing calls this explicitly. It applies to every repository except the types in
`AuditEntitySupport.EXCLUDED_TYPES` (`AuditLog`, `LogSheetActionLog`, `LogSheetEntry`,
`LogSheetVoidSubmission`).

### ⚠ The failure mode worth knowing about

**This is an unbounded producer against a bounded queue with the default abort policy.**

A bulk operation enqueues one audit task per saved row. Measured against live data: a
1,143-row `main_functions` import produced exactly 1,143 `audit_log` rows. A 7,000-row asset
import enqueues roughly 14,000 tasks (asset + activation history), plus ~280 more from
progress updates.

The queue holds 256. When it fills, `ThreadPoolTaskExecutor` rejects with:

```
TaskRejectedException: ExecutorService in active state did not accept task
```

and that exception is thrown **into the calling thread** — the import — which then fails with
a message naming an executor that has nothing to do with importing, and no row number.

"active state" means the pool is healthy and merely full, which is why the message reads as
nonsense in context.

**Not yet fixed.** The one-line remedy is a `CallerRunsPolicy` on `auditExecutor`, so a full
queue makes the producer do the insert itself — the import slows to the rate audit can sustain
instead of dying. Anything that writes thousands of entities in a loop has to reckon with this
until then.

## Excel import jobs

| | |
|---|---|
| **Code** | [`ImportJobService`](../src/main/java/com/hnp/backendofflinefirst/service/importjob/ImportJobService.java), [`ImportJobRunner`](../src/main/java/com/hnp/backendofflinefirst/service/importjob/ImportJobRunner.java), [`ExcelImportService`](../src/main/java/com/hnp/backendofflinefirst/service/ExcelImportService.java) |
| **Pool** | `importExecutor` — **1 thread**, queue 16 |
| **UI** | `/batch-import` |

```properties
app.import.storage-path=${APP_IMPORT_STORAGE_PATH:./data/imports}
app.import.max-stored-errors=${APP_IMPORT_MAX_STORED_ERRORS:500}
app.import.max-rows=${APP_IMPORT_MAX_ROWS:10000}
app.import.async.core-pool-size=${APP_IMPORT_ASYNC_CORE_POOL_SIZE:1}
app.import.async.max-pool-size=${APP_IMPORT_ASYNC_MAX_POOL_SIZE:1}
```

**Deliberately serial.** Pool size 1, and `assertNoActiveImport()` refuses a submission while
any job is `PENDING` or `RUNNING`. Two concurrent imports of overlapping master data would
race on the uniqueness checks and produce results neither user could explain.

### The flow

1. `submit()` — permission check, `.xlsx` check, no-active-import check, store the file, count
   rows, enforce `max-rows`, insert the job as `PENDING`.
2. `scheduleRun()` — registers an **`afterCommit`** hook rather than dispatching immediately.
   The async thread must not look up a row the submitting transaction has not committed yet.
3. `ImportJobRunner.runAsync` — marks `RUNNING`, dispatches on `entity_type`, streams rows.
4. `complete()` / `fail()` / `cancelComplete()` — final status, then the uploaded file is
   deleted either way.

### Progress and cancellation

`ImportProgressListener` fires **every 25 rows**, and does two things: writes
`processed_rows`, and checks the cancel flag.

```java
ImportProgressListener progress = (processed, total) -> {
    if (importJobService.isCancellationRequested(jobId)) throw new ImportJobCancelledException();
    importJobService.updateProgress(jobId, processed, total);
};
```

**Cancellation is cooperative.** `ImportJobCancellationRegistry` is an in-memory
`ConcurrentHashMap` of flags; pressing Stop raises a flag that the *running thread* reads at
the next tick. Flags do not survive a restart, which is fine — neither does the job.

### ⚠ Known limitations

- **Cancel does nothing if the worker thread is gone.** The flag is read only from inside the
  progress listener. A job whose thread died leaves a row stuck at `RUNNING` that Stop cannot
  touch.
- **Delete refuses active jobs** ("Stop the import job before deleting it"), so a stuck job
  cannot be removed either.
- **`assertNoActiveImport()` then blocks every future import**, and `recoverStaleRunningJobs`
  runs only at boot — so the only way out is a restart.
- **Errors do not stop the run.** Row failures are collected and processing continues to the
  end; this is intentional (you want the whole error list, not the first one), but it means a
  file where every row is invalid still runs to completion.
- **The audit queue can kill an import**, as described above.

## Audit retention purge

| | |
|---|---|
| **Code** | [`AuditRetentionService`](../src/main/java/com/hnp/backendofflinefirst/service/AuditRetentionService.java) |
| **Trigger** | **Manual only** — an admin presses a button. There is no schedule. |
| **Pool** | Its own `Executors.newSingleThreadExecutor` — not `auditExecutor` |

Deletes `audit_log` rows older than `app_settings.auditRetentionDays`.

```java
long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
```

**Manual on purpose.** Audit rows are a compliance record; how long they are kept is a policy
decision somebody has to make deliberately, not something a default cron quietly enforces.

**Deletes in batches** of `app.audit.retention.batch-size` (default 5,000) rather than one
statement, so a purge of millions of rows does not hold one enormous transaction or lock the
table against live writes.

Progress is an in-memory `AtomicReference<AuditRetentionProgress>` — one purge at a time,
cancellable, and the state is lost on restart (the deletes already committed are not).

**Its own single-thread executor** so a long purge cannot occupy audit-writing threads. A
retention pass that blocked new audit rows would be self-defeating.

---

# 4. Synchronous work that behaves like a job

## The mobile sync batch

`POST /api/log-sheets/batch` is **not** async — it runs in one database transaction on the
request thread.

```properties
app.sync.batch-max-items=${APP_SYNC_BATCH_MAX_ITEMS:500}
```

The cap exists so one oversized batch cannot tie up a connection and a thread for an unbounded
time. A real offline backlog is tens to low hundreds of items per device, not thousands.

Synchronous is the right choice here: the device needs to know, in the response, which items
were accepted so it can clear them from its queue. An async job would mean a second round trip
and a much harder reconciliation.

---

# Configuration summary

| Property | Default | What it controls |
|---|---|---|
| `app.scheduler.log-sheet-gen-ms` | 60000 | Generation tick |
| `app.scheduler.log-sheet-expiry-ms` | 60000 | Expiry tick |
| `app.scheduler.log-sheet-max-backfill` | 0 | Missed runs generated after an outage |
| `app.attachments.sweep.enabled` | true | Nightly orphan sweep on/off |
| `app.attachments.sweep.grace-hours` | 24 | Minimum file age before deletion |
| `app.attachments.sweep.cron` | `0 0 2 * * *` | When it runs |
| `app.attachments.sweep.zone` | `Asia/Tehran` | Which "2 a.m." |
| `app.audit.enabled` | true | Audit trail on/off |
| `app.audit.async.core-pool-size` | 2 | Audit writer threads |
| `app.audit.async.max-pool-size` | 4 | Audit writer ceiling |
| `app.audit.retention.batch-size` | 5000 | Rows per purge batch |
| `app.import.max-rows` | 10000 | Rows accepted per Excel file |
| `app.import.max-stored-errors` | 500 | Error rows kept per job |
| `app.import.async.core-pool-size` | 1 | Import threads (keep at 1) |
| `app.sync.batch-max-items` | 500 | Items per mobile sync batch |

## Debugging checklist

**A scheduled job is not running:**
1. `@EnableScheduling` present? (It is, on `AsyncConfig`.)
2. For generation: is the template `SCHEDULED`, `schedule_active`, `active`, and is
   `next_run_at` in the past?
3. Check the log for `[SCHEDULER]` business events and per-template errors.

**An import is stuck:**
1. `SELECT id, status, processed_rows, total_rows, error_message FROM import_jobs ORDER BY id DESC;`
2. `RUNNING` with `processed_rows` not advancing → the worker is gone. Restart is currently the
   only remedy.
3. `error_message` mentioning `ExecutorService` → the audit queue overflowed, not the import.

**Attachments are accumulating on disk:**
1. Is `app.attachments.sweep.enabled` true?
2. Files under `grace-hours` old are skipped by design.
3. Run it by hand from the settings page and read the counters — "rows with missing files" is
   the number that should worry you, and it is never repaired automatically.

## Related

- **[schema.md](schema.md)** — the tables these jobs write
- **[log-sheets.md](log-sheets.md)** — the lifecycle generation and expiry drive
- **[AGENTS.md](../AGENTS.md)** — the audit-queue trap, written up as a rule
