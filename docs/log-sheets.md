# Log Sheets — the Business Model

A **log sheet** is one round of readings: an operator walks a set of equipment, scans each NFC
tag, records the readings, and submits. Everything else in this system exists to produce, carry
or interpret log sheets.

This document covers how one is created, every state it can be in, what moves it between them,
and every endpoint that touches it.

---

# 1. Template → Log Sheet

A **template** describes a round that repeats. A **log sheet** is one occurrence of it.

```
LogSheetTemplate                          LogSheet (one per occurrence)
  scope: where               ─┐            entries: one row per asset
  class: which readings       ├─ generate ─►  frozen asset list
  operational unit: who       │             frozen field definitions
  recurrence: how often      ─┘             own status and deadline
```

## What a template holds

| Field | Meaning |
|---|---|
| `scope_type` + `scope_id` | Where: `LOCATION` / `SYSTEM` / `MAIN_FUNCTION` / `SUB_FUNCTION` + that row's id |
| `class_id` | Which class of asset is read, which decides the form |
| `operational_unit_id` | Which unit is responsible |
| `restrict_scope_to_unit` | Whether to additionally filter to that unit's own locations |
| `asset_selection_mode` | `SCOPE` or `EXPLICIT` |
| `generation_mode` | `MANUAL` or `SCHEDULED` |
| `recurrence_unit` × `recurrence_every` | `MINUTE`/`HOUR`/`DAY`/`WEEK`/`MONTH` × N |
| `completion_window_minutes` | How long a generated sheet stays open |
| `schedule_active`, `next_run_at`, `last_run_at` | Scheduler state |

Creating and editing templates is restricted to **ADMIN and HIGH_USER**. A template decides
what the plant records; it is not an operational setting.

## SCOPE vs EXPLICIT

```java
if (template.getAssetSelectionMode() == AssetSelectionMode.EXPLICIT) {
    return resolveExplicitAssets(template);   // the frozen list in log_sheet_template_assets
}
return resolveScopedAssets(template);         // resolved from the hierarchy, now
```

| Mode | Assets on each generated sheet |
|---|---|
| **`SCOPE`** (default) | Resolved from the hierarchy **at generation time** — new equipment installed under the scope appears on the next round automatically |
| **`EXPLICIT`** | A hand-picked list in `log_sheet_template_assets` — never grows by itself |

`SCOPE` is right for "every pump in Unit 3." `EXPLICIT` is right for a specific list somebody
curated and does not want silently extended.

**Inactive assets are skipped in both modes.** A retired pump must not appear on tomorrow's round.

## Two things are frozen at generation

This is the design decision that makes historical data trustworthy.

1. **The asset list.** `log_sheet_entries` rows are written when the sheet is generated.
   Installing new equipment afterwards does not retroactively add it to a round already issued.

2. **The field definitions.** `log_sheets.field_definitions_snapshot` (JSONB) holds the form
   schema as it was. Change a warning band today and last month's sheet still renders — and
   still *re-validates* — exactly as the operator saw it.

Without the snapshot, every historical reading would silently re-interpret itself each time an
engineer adjusted a limit, and an audit of "what did we know at the time" would be impossible.

`template_name` and `scope_summary` are copied onto the sheet for the same reason: renaming a
template must not rewrite the history of rounds run under the old name.

## Scheduled generation

`LogSheetScheduler.generateDueSheets()` runs every 60 s and picks up templates where
`generation_mode = SCHEDULED AND schedule_active AND next_run_at <= now`.

Backfill after an outage is capped by `app.scheduler.log-sheet-max-backfill`, **default 0** —
no backfill. Nobody walked the plant during the outage; manufacturing sheets that claim
otherwise would be worse than the gap. See [jobs.md](jobs.md#log-sheet-generation).

---

# 2. The state machine

```
                          generate
                             │
                             ▼
      ┌──────────────────► PENDING ◄──────────────────┐
      │                   (in the pool)               │
      │                    │       │                  │
      │              claim │       │ assign           │ release
      │                    ▼       ▼                  │
      │                  ASSIGNED ─────────────────►──┘
      │                     │
      │               start │ (first draft save)
      │                     ▼
      │                IN_PROGRESS
      │                     │
      │            complete │ / submit
      │                     ▼
      │  unvoid         SUBMITTED ──── void ────► VOIDED
      └──────────────────── │ ◄─────────────────────┘
                            │
                     reopen │ / admin-reopen
                            └──────────────► ASSIGNED / PENDING

  From PENDING / ASSIGNED / IN_PROGRESS:
      due_at passes, no draft  ──► EXPIRED   ──┐
      due_at passes, has draft ──► SUBMITTED   │ extend
      cancel                   ──► CANCELLED ──┘
```

## The seven states

| Status | Meaning | Who can act |
|---|---|---|
| `PENDING` | Generated, in the **pool**, nobody has it | Any eligible operator may claim; a supervisor may assign |
| `ASSIGNED` | Somebody owns it, no data yet | The assignee; a supervisor may reassign or take over |
| `IN_PROGRESS` | A draft has been saved | The assignee |
| `SUBMITTED` | Completed | Supervisor may void or reopen |
| `VOIDED` | Submitted, then rejected as invalid | Supervisor may un-void |
| `EXPIRED` | Deadline passed with **no data recorded** | Supervisor may extend, which reopens it |
| `CANCELLED` | Called off deliberately | Supervisor may extend, which reopens it |

**`EXPIRED` and `CANCELLED` are different facts and must stay separate.** A deadline that
passed is a compliance failure; a round somebody deliberately called off is not. Merging them
made the compliance report count deliberate cancellations as missed rounds.

## Expiry branches on whether data exists

```java
if (sheet.getDraftSavedAt() != null) {
    logSheetService.finalizeDraftOnExpiry(sheet.getId(), now);   // → SUBMITTED
} else {
    logSheetService.tryExpireOverdue(sheet.getId(), now);        // → EXPIRED
}
```

**A draft is submitted, not discarded.** An operator who walked the plant, recorded readings
and ran out of time produced real measurements that cannot be retaken. Throwing them away
because a clock ran out would destroy field data.

Consequence worth knowing: an auto-finalised draft raises asset status change requests like any
other completion, and for a pool sheet its assignee may be null — which is how a status request
with no actor is created.

## The action log

Every transition writes a `log_sheet_action_log` row: `GENERATE`, `CLAIM`, `RELEASE`, `ASSIGN`,
`REASSIGN`, `TAKEOVER`, `EXTEND`, `ADMIN_REOPEN`, `VOID`, `UNVOID`, `CANCEL`, `START`,
`COMPLETE`, `SUBMIT`, `EXPIRE`, `SUPERSEDE`.

Two timestamps, and the difference is the point:

- **`action_at`** — when the operator did it, on the tablet, possibly offline
- **`recorded_at`** — when the server heard about it

A round claimed at 08:00 and synced at 14:00 must report 08:00.

`client_action_id` is a **unique** idempotency key minted by the device. A sync that times out
and retries writes the action once. It is what makes a flaky link safe.

`EXTEND`, `CANCEL`, `VOID`, `UNVOID` and `ADMIN_REOPEN` carry a **comment** — these are
supervisor overrides of the normal flow, and an override with no stated reason is unauditable.

---

# 3. Filling a sheet

## On the tablet (PWA)

1. Open the sheet from the inbox; it downloads as a **bundle** (`GET /api/log-sheets/{id}/bundle`)
   with entries, field definitions and asset details, so the round can be walked with no network.
2. For each asset: **scan the NFC tag**. Record 1 must contain the expected tag, and by default
   the chip's hardware serial must match too.
3. Record the readings. Photo / audio / video / GPS fields capture to local storage.
4. Save a draft as often as you like — it stays on the device.
5. Submit. The sheet queues for sync; attachments upload on a separate queue.

If a chip will not read, the operator files an **NFC fault report**, which unlocks manual entry
for that asset. The report is what turns "the scan was bypassed" from a silent hole into a
maintenance ticket. `entry_source` records which happened: `PWA_NFC` or `PWA_MANUAL`.

## Correcting a sheet the tablet already submitted

Once a submission reaches the server, the device cannot take it back — that is deliberate, or an
operator could reopen work a supervisor considers final. The way back is
`POST /log-sheets/{id}/reopen`, which returns the sheet to `IN_PROGRESS` (or `PENDING` when it
has no assignee) with a new deadline and the entry values untouched.

**`extend` will not do this.** It refuses a `SUBMITTED` or `VOIDED` sheet; it is the lever for
`EXPIRED` and `CANCELLED` ones. Reopening a completed sheet is `reopen`.

The reopened sheet is back in that operator's inbox (`findAssignedTo` covers
`ASSIGNED`/`IN_PROGRESS`), so their tablet learns about it on the next sync and offers a
**«ادامه‌ی کار»** action which returns its local row to an editable draft, keeping the readings
and minting a fresh `client_action_id` — the old one is already recorded here, and replaying it
would be answered `DUPLICATE` with the corrected values silently dropped. See the PWA's
`docs/sync.md § Reopening a delivered completion`.

Two consequences of reopening, both worth stating before a supervisor uses it:

- **A reopened sheet that is never resubmitted expires.** `reopen` clears `submitted_at`,
  `completed_at` and `draft_saved_at`, so when the new deadline passes the scheduler takes the
  *no data recorded* branch and marks it `EXPIRED` — a completed round becomes a missed one in
  the compliance report. It does not revert to `SUBMITTED`.
- **Re-completing raises a fresh asset status request** if the corrected reading still differs
  from what the asset holds by then. The requests the first completion raised are not withdrawn;
  the supervisor decides both, under the only-latest rule.

## In the web panel

`GET /log-sheets/{id}/fill` renders the same form. `entry_source` is `WEB`. There is no NFC
step — a supervisor at a desk is not standing next to the equipment, and pretending otherwise
would make the data-quality report meaningless.

The two paths differ deliberately on the `location` field type: the PWA **captures** GPS from
the device, the web panel offers **two numeric inputs**. See
[README § GPS location field type](../README.md).

---

# 4. Validation and severity

Each field definition may carry warning and danger bands in its `validation` JSONB. On every
submit, `EntrySeverityEvaluator` computes:

- **`max_severity`** — `OK`, `WARNING` or `DANGER`, the worst across all fields
- **`breached_fields`** — which fields breached, as JSONB

Both are stored on the entry, so the exceptions report is an indexed lookup rather than a
re-evaluation of every reading ever taken.

> **`max_severity IS NOT NULL` is the exact test for "this entry carries a reading."**
> It is set on every submit and nulled when `form_data` is empty. Counting entry *rows*
> instead is what once made the data-quality report claim a 2% manual-entry rate when the
> truth was 67%, and show zero silent assets when 46 had gone uninspected. Two partial
> indexes exist to make this predicate cheap.

Validation runs against the **sheet's own snapshot**, not the current field definitions, so a
sheet re-validates the way it was filled.

---

# 5. Asset status requests

**Completing a log sheet proposes an asset status change. It does not make one.**

If a class has a `status` field and the reading differs from the asset's current status,
completion raises a row in `asset_status_change_requests` with `status = PENDING`. The asset's
own `status` column does not move.

```
reading differs ──► PENDING request ──┬── approve ──► asset.status changes + history row
                                      ├── reject  ──► nothing changes
                                      └── (undo)  ──► back to PENDING, asset restored
```

A supervisor may also raise one manually, without any log sheet.

### The only-latest guard

**Only an asset's newest request may be undone or rejected after approval.** Undoing one in the
middle would roll the asset back over decisions taken since. This is what
`idx_ascr (asset_id, id DESC)` serves.

### Three status columns, none redundant

| Column | Set when | Why separate |
|---|---|---|
| `previous_status` | raised | what the asset read **at the time of the reading** — context for the decider |
| `requested_status` | raised | the proposed value |
| `applied_old_status` | **approved** | what the approval actually **replaced** — the only safe value to restore on an undo |

The first and third diverge whenever anything else moved the asset in between, which is exactly
when an undo would otherwise get it wrong.

### The timestamp is the reading's, not the approval's

`asset_status_history.changed_at` is when the **reading** was taken. A supervisor approving
Monday's round on Wednesday must not stamp Wednesday onto the plant record. This is what
`asset_status_change_requests.reading_recorded_at` (migration V2) carries.

---

# 6. When the server rejects a submission

A tablet may sync a round the server will not accept: the sheet expired, was cancelled, or
somebody else already submitted it.

**The payload is not dropped.** It is stored in `log_sheet_void_submissions` with the complete
submission as JSONB. The operator did the work; discarding real field data over a timing
problem would be the worst possible outcome. A supervisor reviews it and can un-void the sheet
if the readings should stand.

---

# 7. Endpoints

## Web panel — `/log-sheets`

[`LogSheetWebController`](../src/main/java/com/hnp/backendofflinefirst/web/LogSheetWebController.java)

| Method | Path | Purpose |
|---|---|---|
| GET | `/log-sheets` | List, with status/unit/date filters |
| GET | `/log-sheets/export` | Excel export of the filtered list |
| GET | `/log-sheets/{id}` | Detail — entries, readings, media, action history |
| GET | `/log-sheets/{id}/fill` | The fill form |
| POST | `/log-sheets/{id}/draft` | Save a draft |
| POST | `/log-sheets/{id}/complete` | Complete and submit |
| POST | `/log-sheets/generate` | Generate one now from a template |
| POST | `/log-sheets/custom` | Create an ad-hoc sheet with hand-picked assets |
| GET | `/log-sheets/options/units` | Searchable unit picker (scoped) |
| GET | `/log-sheets/options/assets` | Searchable asset picker (scoped) |

### Lifecycle actions

| Method | Path | Effect |
|---|---|---|
| POST | `/log-sheets/{id}/claim` | `PENDING` → `ASSIGNED` (self) |
| POST | `/log-sheets/{id}/release` | `ASSIGNED` → `PENDING` (back to the pool) |
| POST | `/log-sheets/{id}/assign` | Supervisor assigns to an operator |
| POST | `/log-sheets/{id}/reassign` | Move to a different operator |
| POST | `/log-sheets/{id}/takeover` | Supervisor takes it themselves |
| POST | `/log-sheets/{id}/extend` | New deadline; reopens `EXPIRED` / `CANCELLED` |
| POST | `/log-sheets/{id}/cancel` | → `CANCELLED`, with a comment |
| POST | `/log-sheets/{id}/void` | `SUBMITTED` → `VOIDED`, with a comment |
| POST | `/log-sheets/{id}/unvoid` | `VOIDED` → `SUBMITTED` |
| POST | `/log-sheets/{id}/reopen` | Reopen a submitted sheet with a new deadline |
| POST | `/log-sheets/{id}/admin-reopen` | Admin override reopen |

### Attachments and void submissions

| Method | Path | Purpose |
|---|---|---|
| POST | `/log-sheets/{id}/attachments` | Upload from the web fill form |
| GET | `/log-sheets/{id}/attachments/{attachmentId}` | Stream a file |
| POST | `/log-sheets/{id}/attachments/{attachmentId}/delete` | Delete |
| GET | `/log-sheets/{id}/void-submissions/{voidId}` | Inspect a rejected payload |

## Mobile API — `/api/log-sheets`

[`LogSheetController`](../src/main/java/com/hnp/backendofflinefirst/controller/LogSheetController.java)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/log-sheets/inbox` | The operator's sheets: assigned + claimable pool |
| GET | `/api/log-sheets/{id}/bundle` | Everything needed to fill the sheet offline |
| GET | `/api/log-sheets/{id}/entries` | Entries alone |
| POST | `/api/log-sheets/{id}/claim` | Claim from the pool |
| POST | `/api/log-sheets/{id}/release` | Return to the pool |
| POST | `/api/log-sheets/{id}/assign` | Assign (supervisor) |
| POST | `/api/log-sheets/{id}/reassign` | Reassign (supervisor) |
| **POST** | **`/api/log-sheets/batch`** | **The sync endpoint** — drafts, submissions and actions in one call |

Supporting endpoints:

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/bootstrap` | Master data + settings for the device |
| GET | `/api/asset-entries/nfc/{nfcTagId}` | Resolve a scanned tag |
| POST | `/api/asset-entries/{id}/nfc-serial` | Record a chip's hardware serial |
| POST | `/api/attachments` | Upload media (multipart) |
| GET | `/api/attachments/{id}` | Download |
| DELETE | `/api/attachments/{id}` | Delete |
| POST | `/api/nfc-fault-reports/batch` | Sync fault reports |
| POST | `/api/auth/login` | Obtain a JWT |
| GET | `/api/health` | Liveness |

### The attachment endpoints

`POST /api/attachments` is keyed by a **client-minted id**, so re-sending a file after a
timeout returns the existing row instead of storing a second copy.

Each `(log sheet, asset, field, kind)` has a ceiling —
`attachments.max_images_per_field` / `max_audios_per_field` / `max_videos_per_field`, counted
over the **server's own** rows. Exceeding it answers **409 Conflict**, not 400:

> A full field is a statement about *state*, not about the file. The device parks a 4xx as a
> permanent refusal so it stops wasting a link on bytes the server will never accept — correct
> for a rejected type or an oversized file, and wrong here, because the very next deletion makes
> the same upload legal. Returning 400 parked the operator's replacement photo for good.

`DELETE /api/attachments/{id}` is therefore what frees a slot, and the device treats a 404 as
success — the end state is what matters. Deleting before the sheet is submitted removes the
server's copy; after submission the device keeps its own row only, because a delivered
attachment is evidence. See the PWA's `docs/sync.md` for the device half.

### The batch endpoint

`POST /api/log-sheets/batch` runs **synchronously in one transaction**, capped by
`app.sync.batch-max-items` (default 500). Synchronous on purpose: the device needs to know in
the response which items were accepted so it can clear its queue. Every item carries a
`client_action_id`, so a retry after a timeout is safe.

---

# 8. Access control

Scope is the **reporting** scope — responsibility through a log sheet — not location ownership.
A supervisor sees the rounds their unit is accountable for, including sheets carrying assets
from outside their own locations (a template with `restrict_scope_to_unit = false`).

See [hierarchy.md](hierarchy.md#5-access-scope--the-part-that-must-be-right).

Every endpoint is gated by a permission whose code **is the route** —
`POST:/log-sheets/{id}/void`, braces included. Adding an endpoint means adding a permission row
in a migration, or nobody but a superuser can reach it.

---

# 9. Adding a new lifecycle action: a checklist

1. Add the value to `LogSheetActionType`.
2. Implement the transition in `LogSheetAssignmentService` / `LogSheetService`, with an explicit
   guard on the states it is legal from.
3. Write the `log_sheet_action_log` row — with a comment if it is a supervisor override.
4. Add the permission row in a **new** migration (`V{n}`).
5. Add the web endpoint and the button, gated by `sec:authorize` on that exact permission code.
6. If the mobile app can perform it, add it to the batch endpoint and give it a
   `client_action_id`.
7. Test with a **scoped** user, not only an admin.
8. Update this file and [schema.md](schema.md) in the same commit.

## Related

- **[schema.md](schema.md)** — the tables
- **[hierarchy.md](hierarchy.md)** — how scope decides what lands on a round
- **[jobs.md](jobs.md)** — generation and expiry
- **[reports.md](reports.md)** — what is measured from all this
- **[security.md](security.md)** — who may perform each transition, and the service-layer rules behind the endpoint permissions
