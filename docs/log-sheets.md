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

## How many assets one sheet may hold

`app.log-sheets.max-assets-per-sheet` (300) and `app.log-sheets.warn-assets-per-sheet` (150).
Nothing bounded this before: a `SCOPE` template resolves to however many assets the hierarchy
holds, and the count grows on its own as the plant does.

**The maximum is refused in one place and only warned about in another, and that is the design.**

| Where | Behaviour | Why |
|---|---|---|
| Saving a template, creating a custom sheet | **Refused** above the maximum, with the actual count and the limit in the message | A person is at the scope picker. Narrowing it now costs one edit |
| The preview page (`/log-sheet-templates/{id}/preview-assets`) | A banner above the count | The only screen that shows what a `SCOPE` template currently resolves to |
| The scheduler | **Generates anyway**, and warns naming the template | A `SCOPE` template re-resolves every run, so one that passed validation crosses the limit the day new equipment is registered — with nobody having edited it. Refusing would mean the round silently does not happen and the shift finds no work, with nothing to look at but an absence |

What actually breaks first is not the database. It is the web fill page, which renders every
entry in one form and resubmits all of them on every save — past Tomcat's `max-parameter-count`
the extra parameters are dropped *silently*. Then the tablet, where saving one asset rewrites the
whole entries array into IndexedDB. Then the round itself: a sheet is one operator's claim, and
one that cannot be finished in a shift cannot be split between two people either.

`max <= 0` disables the refusal for a site that genuinely wants one enormous sheet. The warning
still fires.

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
      due_at passes             ──► EXPIRED   ──┐ extend
      cancel                    ──► CANCELLED ──┘

  Review, laid on top of completion:
      SUBMITTED ──── approve ────► APPROVED
          ▲                           │
          └──────── unapprove ────────┘

      void / reopen / extend all REFUSE an APPROVED sheet: it must be
      unapproved first, so the history says who withdrew the sign-off.
```

## The eight states

| Status | Meaning | Who can act |
|---|---|---|
| `PENDING` | Generated, in the **pool**, nobody has it | Any eligible operator may claim; a supervisor may assign |
| `ASSIGNED` | Somebody owns it, no data yet | The assignee; a supervisor may reassign or take over |
| `IN_PROGRESS` | Work has started — a web draft saved, or a tablet's first progress report | The assignee |
| `SUBMITTED` | Completed, **awaiting review** | Supervisor may approve, void or reopen |
| `APPROVED` | Completed and **accepted by a supervisor** | Supervisor may withdraw the approval; nothing else |
| `VOIDED` | Submitted, then rejected as invalid | Supervisor may un-void |
| `EXPIRED` | Deadline passed. **Every overdue round expires, whatever it recorded** — the readings stay, the round is simply not submitted | Supervisor may extend, which reopens it with its values intact |
| `CANCELLED` | Called off deliberately | Supervisor may extend, which reopens it |

**`EXPIRED` and `CANCELLED` are different facts and must stay separate.** A deadline that
passed is a compliance failure; a round somebody deliberately called off is not. Merging them
made the compliance report count deliberate cancellations as missed rounds.

## Approval — the review step after completion

A completed round is **delivered**, not **accepted**. `APPROVED` records that a supervisor read
it and signed it off.

```
POST /log-sheets/{id}/approve     SUBMITTED → APPROVED   (comment optional)
POST /log-sheets/{id}/unapprove   APPROVED  → SUBMITTED  (comment optional)
```

`approved_at` and `approved_by_user_id` are set together with the status and cleared together
with it — the same pattern as every other transition's companion timestamp.

**The approver may be the person who completed the round.** No check forbids it, deliberately: on
a small site the supervisor often walks the round themselves, and a rule that made the common case
impossible would only teach people to complete rounds under somebody else's login.

**One door in, one door out.** `APPROVED` is reachable only from `SUBMITTED` and leaves only to
`SUBMITTED`. `void`, `reopen` and `extend` all refuse it — so a round that is to be reopened or
voided must have its approval withdrawn first, and the history says who withdrew it and why. The
alternative — letting `void` take an approved sheet directly — loses the fact that somebody had
accepted it.

**A late offline submission cannot overwrite an approved round.** `COMPLETABLE_STATUSES`
deliberately excludes `APPROVED`, so a tablet that has been offline since before the approval gets
its payload preserved as a **void submission** (§6) rather than silently replacing reviewed work.

### Why this is a status, and what that costs

It is a lifecycle state, and this schema already models lifecycle in `status` with a companion
timestamp per transition. A nullable `approved_at` alone would have been cheaper — no condition
anywhere would have needed touching — but it would have left "what does `status = VOIDED` with
`approved_at` set mean?" permanently open.

The cost is that **every condition asking "was this round completed" now has to accept two
values**, and there are about forty of them across services, reports and templates. A missed one
is silent: no error, just a smaller number in a report about a plant. So the rule is mechanical
rather than remembered:

- `LogSheetStatus.COMPLETED_STATUSES` / `isCompleted()` is the **only** way to ask.
- `LogSheetViewHelper.isCompleted / isAwaitingApproval / isApproved` are the only way a template
  asks — no template names a status itself.
- [`CompletedStatusConditionTest`](../src/test/java/com/hnp/backendofflinefirst/CompletedStatusConditionTest.java)
  **fails the build** for anything naming `SUBMITTED` alone outside a short allow-list of files
  that hold genuine transition guards (`LogSheetAssignmentService`, `LogSheetService`,
  `LogSheetStatus`, `LogSheetViewHelper`).

`isAwaitingApproval(status)` means *exactly* `SUBMITTED` and is the precondition for approve, void
and reopen alike. It exists so the buttons and the service can never disagree — a supervisor
offered an action the service will refuse is worse than not being offered it.

**No backfill.** Every pre-existing `SUBMITTED` sheet stayed `SUBMITTED`; nobody approved them,
and stamping an approval nobody performed would be inventing a review. Expect the "awaiting
approval" figure to be large on the first day. That number is true.

**On the tablet.** `APPROVED` is a delivered round and the PWA treats it exactly as `SUBMITTED`
(`isCompletedServerStatus`). The danger there is not different behaviour on purpose but different
behaviour by *falling through*: an unhandled status makes `alignLocalWorkflowWithServer` return
null — "nothing to do" — which leaves a stale local draft alive for a round the server has
closed. See the PWA's `docs/sync.md`.

## Expiry no longer branches on whether data exists

```java
logSheetService.tryExpireOverdue(sheet.getId(), now);   // → EXPIRED, for every overdue round
```

A sheet carrying `draft_saved_at` used to be auto-submitted as its own final record. That branch
is gone, and the change is smaller than it looks: **expiry has never touched
`log_sheet_entries`.** Every reading stays exactly where it was written; only the sheet's status
differs. What changed is who decides that a partial round counts as done — a supervisor, by
extending the deadline (which reopens the round with its values intact) and completing it, rather
than the scheduler at the moment a clock ran out.

It also had to go for progress sync to be safe: `draft_saved_at` now has two writers, and keeping
the branch would have auto-submitted every mobile round the moment its deadline passed —
finalising work an operator was still walking.

The consequences, and the lever for each, are in [jobs.md](jobs.md#log-sheet-expiry). The one
worth knowing here: **an expired round raises no asset status change request**, so a status
observed in the field is never proposed until somebody extends and completes the sheet. The
«پیشرفت» column on the log-sheet list is what makes an expired-but-substantially-walked round
visible enough to act on.

## The action log

Every transition writes a `log_sheet_action_log` row: `GENERATE`, `CLAIM`, `RELEASE`, `ASSIGN`,
`REASSIGN`, `TAKEOVER`, `EXTEND`, `ADMIN_REOPEN`, `VOID`, `UNVOID`, `APPROVE`, `UNAPPROVE`,
`CANCEL`, `START`, `COMPLETE`, `SUBMIT`, `EXPIRE`, `SUPERSEDE`.

**`START` is written once per round, by the first progress report a tablet sends.** It existed in
the enum from the beginning and nothing wrote it, because until progress sync a tablet made no
draft save the server could see — so a sheet's history jumped from `CLAIM` straight to `COMPLETE`
with hours in between and nothing to say when the operator actually began. It is guarded on
`started_at IS NULL` inside the same atomic update that sets it, so a round reporting every thirty
seconds for a whole shift still produces exactly one.

Two timestamps, and the difference is the point:

- **`action_at`** — when the operator did it, on the tablet, possibly offline
- **`recorded_at`** — when the server heard about it

A round claimed at 08:00 and synced at 14:00 must report 08:00.

`client_action_id` is a **unique** idempotency key minted by the device. A sync that times out
and retries writes the action once. It is what makes a flaky link safe.

`EXTEND`, `CANCEL`, `VOID`, `UNVOID`, `APPROVE`, `UNAPPROVE` and `ADMIN_REOPEN` carry a
**comment** — these are supervisor overrides of the normal flow, and an override with no stated
reason is unauditable.

### A deadline change says what it changed

`EXTEND` and `reopen` both move `due_at`, and the comment used to hold only whatever the
supervisor typed — so the history recorded that a deadline had been extended and not *from what,
to what*. Reading it later, the only way to reconstruct the old deadline was to infer it from
surrounding rows.

The comment's **first line** now carries the change, and the supervisor's own text starts on the
next:

```
مهلت تکمیل از ۱۴۰۴/۰۶/۰۴ ۰۸:۰۰ به ۱۴۰۴/۰۶/۰۴ ۱۴:۰۰ تغییر کرد.
اپراتور در شیفت بعدی ادامه می‌دهد.
```

A sheet that had no deadline at all reads «مهلت تکمیل تعیین شد: …» instead — "changed from
nothing" is not a change.

**No new column, deliberately.** `log_sheet_action_log.comment` already exists, already renders,
and already survives export; a second column would have needed a migration, a display and an
export path of its own to say something the comment can say. The combined text is truncated to
`LogSheetActionLog.MAX_COMMENT_LENGTH` with an ellipsis, so a very long explanation costs its own
tail rather than the header.

---

# 3. Filling a sheet

## On the tablet (PWA)

1. Open the sheet from the inbox; it downloads as a **bundle** (`GET /api/log-sheets/{id}/bundle`)
   with entries, field definitions and asset details, so the round can be walked with no network.
2. For each asset: **scan the NFC tag**. Record 1 must contain the expected tag, and by default
   the chip's hardware serial must match too.
3. Record the readings. Photo / audio / video / GPS fields capture to local storage.
4. Save each asset as you go. The values stay on the device **and**, when there is a link, are
   reported to the server so a supervisor can see how far the round has got — see §3.5.
5. Submit. The sheet queues for sync; attachments upload on a separate queue.

If a chip will not read, the operator files an **NFC fault report**, which unlocks manual entry
for that asset. The report is what turns "the scan was bypassed" from a silent hole into a
maintenance ticket. `entry_source` records which happened: `PWA_NFC` or `PWA_MANUAL`.

## Correcting a sheet the tablet already submitted

Once a submission reaches the server, the device cannot take it back — that is deliberate, or an
operator could reopen work a supervisor considers final. The way back is
`POST /log-sheets/{id}/reopen`, which returns the sheet to `IN_PROGRESS` (or `PENDING` when it
has no assignee) with a new deadline and the entry values untouched.

**`extend` will not do this.** It refuses a completed sheet — `SUBMITTED` *or* `APPROVED` — and a
`VOIDED` one; it is the lever for `EXPIRED` and `CANCELLED` ones. Reopening a completed sheet is
`reopen`, and an **approved** sheet has to be unapproved first: `reopen` refuses `APPROVED` for
the same reason `void` does.

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

## Reading a filled sheet in the panel

The sheet's page lists **every parameter each asset's class defines**, not only the ones somebody
answered — `FormDataViewHelper.allRows`, driven by the sheet's frozen field-definition snapshot
rather than by `form_data`'s keys.

That distinction matters because `form_data` deliberately holds a key only where there is a real
answer (§3, *Who wins when two people have touched the same sheet*, rule 1). Rendering from those
keys meant an asset with three of seven parameters recorded showed three rows, and the four the
operator skipped looked exactly like parameters the class does not have — the one thing a
supervisor is reading the page to establish. An untouched asset showed a bare «—».

Two controls, on two different axes, and they compose:

| Control | Picks |
|---|---|
| «همه / دارای داده / بدون داده» | which **assets** are listed |
| «همه پارامترها / فقط دارای مقدار» | which **parameters** are shown inside each of them |

Both are client-side over rows that are already rendered, so neither costs a round trip and
neither resets the other or the search box.

**An unanswered number is not judged against its warning bands.** A band cannot be breached by an
absent reading, and evaluating one would colour every empty row on any class whose minimum is
above zero.

The voided-submission page deliberately still renders only what the refused payload literally
carried — padding it with parameters that payload never held would misrepresent what was sent.

## In the web panel

`GET /log-sheets/{id}/fill` renders the same fields. `entry_source` is `WEB`. There is no NFC
step — a supervisor at a desk is not standing next to the equipment, and pretending otherwise
would make the data-quality report meaningless.

### One asset at a time

**The page does not edit in place.** Each asset's card shows its stored readings read-only, and a
«تکمیل» / «ویرایش» button opens a dialog holding that asset's inputs. Confirming the dialog posts
that asset alone to `POST /log-sheets/{id}/entries/{entryId}/draft`, which saves it and answers
with the card's summary re-rendered.

Three consequences, each deliberate:

**A round is saved as it is walked.** Forty-seven assets are forty-seven small saves rather than
one submission held in the browser until the end, so closing the tab costs at most the asset
currently open. The label tells the operator which act they are about to perform: «تکمیل» records
a reading, «ویرایش» replaces one somebody already took — and only the second leaves a revision.

**The summary is what is stored, never what was typed.** It is server-rendered through
`fragments/fill-entry-summary`, which delegates to the same `form-data-display :: tableAll` the
detail page uses, and it is re-fetched after every save. The browser never formats a value. The
alternative — rebuilding the table in JavaScript from the inputs — writes the formatting rules
(units, «ثبت نشده», a multiselect's separator, an attachment tile) twice in two languages, and
they drift the first time either is touched.

**The dialogs sit outside the page's form, so «تأیید نهایی» carries no readings at all.** That is
safe because `validateWebFormData` falls back to each entry's stored `form_data` for any entry the
submission does not mention — required fields are still checked across the whole sheet — and
`applyWebEntryValues` skips those entries rather than blanking them. It is also the point: what
gets completed is what was saved, not whatever was left sitting in an input somebody typed into
and then closed with the X. The form still carries the sheet-level notes, which is what the
bottom «ذخیره توضیحات» button saves.

Nothing in the service layer changed to allow any of this. `applyWebEntryValues` already walked
the sheet's entries and skipped any the submitted map did not name, and `applyWebNotes(sheet,
null)` was already a no-op — a map holding one entry and no notes was always a supported shape.
**History therefore behaves exactly as it does on every other web save:** the same
`recordSupersededValue` call, writing a revision only when a meaningful value was actually
replaced.

The two paths differ deliberately on the `location` field type: the PWA **captures** GPS from
the device, the web panel offers **two numeric inputs**. See
[README § GPS location field type](../README.md).

## An operator removed from their unit while offline

**Their whole round now delivers.** Readings, attachments, NFC fault reports, the bundle refresh
and the sheet's own page in the panel — all of it, on the strength of one rule: **the sheet's
current assignee may always reach it, whatever unit they belong to today.**

This used not to be true, and the inconsistency was invisible to everyone involved. `submitOne`
asked only whether the caller was the assignee; everything else went through
`LogSheetAccessService.requireVisibleLogSheet`, which applies unit scope. Removing somebody from
a unit touches neither `assignee_user_id` nor their roles, so the readings were accepted and:

| | Was | Now |
|---|---|---|
| `POST /api/attachments` | ❌ 403 — the round arrived with no photographs | ✅ |
| `POST /api/nfc-fault-reports/batch` | ❌ `ERROR`, which the client parks permanently | ✅ |
| `GET /api/log-sheets/{id}/bundle` and `/entries` | ❌ 403 — the sheet stayed in their inbox but could not be refreshed, so «ادامه‌ی کار» and every online reopen of the fill page failed | ✅ |
| `GET /log-sheets/{id}` in the panel | ❌ 403 — reachable from «کارتابل من», which has never had a unit filter, and then denied | ✅ |
| `POST /api/log-sheets/batch` | ✅ | ✅ |

The branch lives in `LogSheetAccessService.canView`, so every object-level check inherits it at
once — see [security.md §1](security.md#1-the-three-layers).

**The blast radius is one row and one person.** `assignee_user_id` is server data, never a client
parameter, and `release` / `reassign` / `takeover` revoke it the instant ownership moves: a former
assignee is refused again immediately. An operator from another unit who is *not* the assignee is
refused exactly as before.

**Deliberately not applied to the list queries.** `/log-sheets` stays unit-scoped SQL — a sheet in
a unit you no longer belong to does not belong in that unit's listing. Their own work reaches them
through «کارتابل من» and the mobile inbox.

Regression: `AttachmentApiIntegrationTest` § *The assignee's own work, after they leave the unit*,
which keeps the four cross-unit refusals green alongside it.

## Returning a sheet to the pool — and why the tablet cannot

**An operator can release a sheet from the web panel, and deliberately not from the PWA.** This
is a decision, not a gap. Somebody will eventually notice the asymmetry and "fix" it; read this
first.

### What exists today

| Surface | Who sees a release button | Where |
|---|---|---|
| Web panel | anybody with `POST:/log-sheets/{id}/release` — OPERATOR included | the sheet's own page |
| PWA — «کارهای من» | **nobody** | an operator's own assigned sheet offers only «باز کردن» |
| PWA — «کارهای واحد» | supervisors (`isSupervisor`) | `LogSheetListPage`, `variant === 'team'` |

The API endpoint and the permission both exist for operators. It is the tablet's *own-work* card
that does not offer the action.

### The fact that decides it

```ts
// services/auth/sessionContext.ts — the outbound queue
if (sheet.status !== 'submitted') return false
```

**The submit queue still carries only completions**, and that is what the release asymmetry was
argued from. The premise has weakened since progress sync (§3.5): a round being walked with a link
available now reports its values as they are taken, so releasing it no longer necessarily strands
them. It can still strand them — an operator underground has reported nothing — so the reasoning
below stands, but it is now about *the offline case* rather than about every case.

That makes the tempting scenario the dangerous one: an operator fills twenty assets, does not
submit, and hands the sheet back. Those twenty readings never reach the server, the next operator
starts from an empty sheet and repeats the walk. It is not data *corruption* — the work is
archived locally, see below — but it is work silently thrown away, and putting the action one tap
away in the field makes it far more likely than a walk to a PC does.

Two supporting reasons:

- **Release needs the network, and the tablet is where there often is none.** The supervisor's
  release button is already `disabled={!canUseServer}`. An operator underground would get a
  disabled button and no explanation. The panel is by definition used where there is a network.
- **Claiming and releasing are not symmetrical.** Claiming is safe and reversible. Releasing while
  holding unsent work detaches that work from its owner. The friction is doing something useful.

### What actually happens if they do release from the panel

Traced, not assumed:

1. Server: status → `PENDING`, assignee cleared.
2. The tablet's next sync leaves the local draft **untouched** — `isAssigneeMismatch` returns
   false when the server assignee is null, so `alignLocalWorkflowWithServer` returns no action.
   The operator still sees their draft, for a sheet they no longer own.
3. When somebody else claims it, the assignee now differs, `alignLocalWorkflowWithServer` returns
   `reset-draft`, and `applyLogSheetBundle` calls **`archiveLocalWorkBeforeClear` first**. The
   readings survive as a read-only snapshot in `logSheetUserArchives`.

So nothing is lost from the device. Whatever the round had already reported is on the server;
anything taken since the last report is not.

### If this is ever revisited

- **Do not simply add the button.** It must refuse — or hard-confirm — when
  `sheetHasLocalEntryData(sheet)` is true, and the honest version submits first.
- **The cheaper improvement is not a button.** Neither side warns the operator that releasing
  abandons unsent readings, and the panel *cannot* know a tablet holds a draft, because that
  draft never left the device. A marker on the tablet's own-work card — «۲۰ مورد ثبت‌نشده روی این
  دستگاه» — is the information the operator needs before walking to a PC, and it helps in other
  situations too.

## Reporting progress while the round is still being walked

**A round is no longer invisible until it is submitted.** An operator could fill twenty assets in
the first hour, be online the whole time, and a supervisor looking at the sheet saw `ASSIGNED`
with no data at all. If the sheet then changed hands, the next operator opened an empty form and
re-walked ground already covered.

`POST /api/log-sheets/progress` closes that. Every operator save marks the row, and the ordinary
sync tick reports it.

### What it writes, and what it refuses to

It writes the values **straight into `log_sheet_entries`**, through the same merge a mobile submit
uses — `storableFormData`, `wouldBlankUnseenAnswer`, `EntrySeverityEvaluator`, and the
`formDataChanged` gate that decides authorship. That is the whole reason a handover now carries
the first operator's work: their readings are already the sheet's readings, named with their
`filled_by_user_id`, before anybody reassigns anything.

It is **not** a completion, and nothing in it may become one:

| | |
|---|---|
| Sheet status | `ASSIGNED` → `IN_PROGRESS`, `started_at` once, `draft_saved_at` / `draft_saved_by_user_id` / `draft_source = MOBILE` |
| Action log | one `START` row, on the first report only. No `COMPLETE`, no `SUBMIT` |
| `completed_at` / `submitted_at` | untouched |
| Asset status requests | **none.** Completing a round *proposes* an asset's new state; a round in progress proposes nothing, and the operator may still correct the value before they submit |
| A refusal | **no `log_sheet_void_submissions` row.** Nothing was lost: the work is on the device and still deliverable through the ordinary submit path |

### Three rules that make it cheap and safe

**Only what changed is sent.** The device filters on `locallyEditedAt` — the marker that means
"somebody edited this *on this device*" — so the payload is proportional to readings actually
taken rather than to the size of the sheet times the tick rate. A cleared answer is dirty too,
and `wouldBlankUnseenAnswer` still decides whether to honour the blank.

**Idempotency is by value, not by key.** There is no `clientActionId`: progress is *meant* to be
re-sent, and a unique action key would answer the second push `DUPLICATE` and quietly stop the
supervisor's view advancing. Re-sending values the server already holds changes nothing —
`formDataChanged` is false, so no authorship moves and no revision row is written.

**The deadline is judged on the server clock, not on a device time.** The opposite of a
completion, which is judged on `completed_at` so on-time work delivered late is still accepted. A
progress report is about a round still being walked; there is no earlier moment it could belong
to, and accepting one past `due_at` would let a tablet keep editing an expired sheet. A round
finished in time and delivered late is still accepted, by the completion path, exactly as before.

### Validation is partial, on purpose

`validatePartialEntry` skips the required-field check. A push happens at asset seven of forty, so
judging it against "every required field is present" would refuse every push until the last one.
The shape of the answers that *are* there is still checked — a number that is not a number, a
select value outside its options — because those would be refused at submit anyway, and letting
them into `form_data` early would show the supervisor a value the final submission then throws
out.

### Who may report

The **assignee, and only the assignee** — stricter than the submit path, which lets a plant-wide
actor complete a sheet they do not hold. Progress publishes unfinished work under the assignee's
name and stamps `draft_saved_by_user_id` with it, so "I am the person walking this round" is the
whole precondition. Anybody who wants the round has `takeover`, and taking it over is the honest
thing to record. Ownership is re-checked atomically in the same UPDATE that stamps the sheet, so a
takeover, reassign, release or cancel landing mid-push cannot lose.

Its own permission — `POST:/api/log-sheets/progress`, granted by V4 to every role that already
holds `POST:/api/log-sheets/batch`, **derived from that grant rather than from a list of role
codes**, so a duplicated role gets it too. A site can therefore turn the live-progress traffic off
without stopping anybody delivering a round.

### What the supervisor sees

«N از M دارایی» on `/log-sheets` and «کارتابل من», a progress bar and «آخرین ذخیره پیش‌نویس» with
its author and surface on the sheet's own page, and the per-asset values themselves with
`filled_by_user_id` — all of which the detail page already rendered. Visibility lags by at most
one sync interval (30 s by default).

Regression: `LogSheetProgressSyncIntegrationTest`, and `progressSync.test.ts` on the device.

## Who wins when two people have touched the same sheet

Merging is **per entry**, never per field. Two people editing different fields of the *same*
asset is still last-writer-wins, and that is a knowing decision rather than an oversight:
field-level merging would settle that case too, and is a much larger change for a much rarer
conflict.

Within that, three rules — each of which exists because its absence cost real readings:

1. **`form_data` holds only answered fields.** A key is present only when the field has a real
   answer; an untouched asset is `{}`. Both write paths enforce it (`storableFormData`), because
   both send the *whole sheet*: the web form posts every entry on every save, and a mobile submit
   resends every asset on the device. Without it, one supervisor save wrote
   `{"Bar": "", "Status": ""}` onto all 40 entries of a sheet.

2. **The device keeps what somebody edited *on it*, and takes the server's for everything else.**
   Not "where it has values", for two independent reasons. The operator emptying the last field
   *is* an opinion, and reading that as absence let the next sync restore what they had just
   deleted. And a value the device merely *received* is indistinguishable from one it typed —
   after any sync the device is holding this server's own readings — so "the local copy has
   values" is true for every filled entry on every synced device, and a supervisor's correction
   in the browser never reached the tablet, which then wrote the stale reading back over it.

   So the PWA records the opinion when it is formed: `locallyEditedAt`, stamped by every operator
   save including an emptying one, and set by nothing that receives from the server. It is the
   only thing the merge reads. Rows written before the marker existed are stamped once by a
   client-side migration rather than by a permanent fallback. Asking the loosest question of all —
   does the local copy have any *keys* — is what let a device that had been handed blank keys
   treat every asset as its own work and never accept a server value again.

   That marker is cleared the moment the server accepts the work, and ignored outright on a row
   that is already `submitted` + `synced`. Both, not one: without the first a marker outlives its
   submission; without the second, the reopen-and-continue path turns a delivered row back into a
   draft and re-arms whatever is left.

3. **A stale device may not blank an answer it never saw.** `wouldBlankUnseenAnswer` refuses a
   submit that would erase a stored answer when the device's echoed `(createdAt, updatedAt)` pair
   does not match the entry's. Equality, not ordering — for an untouched entry the device is
   echoing the server's own numbers, so device clock skew cannot flip the decision. When the pair
   *does* match, a blank goes through: clearing a reading you can see is a real thing operators do.

Together these are what makes the handover work: operator fills three assets and syncs, a
supervisor reopens the sheet and fills two more in the browser, the sheet comes back to the
operator — and both sets of readings survive, each still naming who recorded it. Regression:
`ReopenedSheetSupervisorEntriesIntegrationTest`, and `mergeLogSheetBundle.test.ts` on the device.

## What a correction replaces is kept

**A supervisor correcting an operator's reading no longer destroys it.** Every overwrite of a
non-empty answer writes the replaced value to `log_sheet_entry_revisions` — the reading, its
severity, how it was captured, who recorded it and when *on the device* — alongside who replaced
it, from which surface, and under what sheet status.

Before V4 this was the one path in the system where a real field measurement disappeared without
trace. The entry kept the new value with `entry_source`, `filled_by_user_id` and `updated_at` all
moved to the supervisor: attribution standing over a value nobody could see any more. Everything
else here goes out of its way not to lose a reading — `log_sheet_void_submissions` keeps a whole
payload the server refused, the tablet keeps `logSheetUserArchives` per user — and this was the
exception.

**Filling an empty entry writes nothing**, so a normal round produces no revisions at all; only
genuine corrections do. Three paths write them, all through one method and all gated on the same
`formDataChanged` flag that decides re-attribution: the mobile submit, the mobile progress push,
and the web fill form. Tying the two together is what stops them drifting — an entry whose
authorship moved without a history row, and a row written for a save that changed nothing, are
each a lie of their own kind.

It covers more than the supervisor case. Operator 2 redoing an asset operator 1 already read
writes one too, which is exactly what the handover flow now produces routinely.

The sheet's page renders it as a collapsed «مقادیر پیشین» panel under each corrected asset. Shape,
retention and the reasoning for storing the *old* value rather than the new one:
[schema.md](schema.md#log_sheet_entry_revisions). Regression:
`LogSheetEntryRevisionIntegrationTest`.

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

The approval queue at `/asset-status-requests` shows **both** times on each row — *ثبت درخواست*
(when the request was created on the server) and *ثبت در دستگاه* (`reading_recorded_at`). They
carry different moments, and the second is the entry's **`created_at`** — the log sheet's
«ثبت داده» column, when the operator first recorded the reading on the device.

> Deliberately **not** `updated_at` («آخرین ویرایش»), which is what it used to be. That column
> moves every time the entry is touched again — a later correction to another field of the
> same asset, a supervisor's edit on the web form — and each of those dragged the recorded
> observation forward to a moment nobody was at the equipment. On approval this value becomes
> `asset_status_history.changed_at`, so the drift landed in the asset timeline too.
> Pinned by `AssetStatusIntegrationTest` § *when the change is dated*, which sets the two
> device times to different values so they cannot be confused again.
diverge by exactly the length of the offline gap, so showing only the first made every synced
round look as though it had been inspected on arrival. The second line is rendered only when the
value is present: a manually raised request has no reading behind it, and rows written before V2
have none either — a `th:if` on the field, not an empty placeholder.

---

# 6. When the server rejects a submission

## The deadline is judged on when the work was done, not when it arrived

This is the rule that makes an offline round deliverable, and it is not guessable from the
status column. `submitIfStillCompletable` compares the deadline against the **device's**
`completed_at`:

```sql
AND s.status IN :completableStatuses
AND (s.dueAt IS NULL OR s.dueAt >= :completedAt)
```

and `COMPLETABLE_STATUSES` **includes `EXPIRED`**:

| Sheet status | Finished before `due_at` | Finished after `due_at` |
|---|---|---|
| `PENDING` / `ASSIGNED` / `IN_PROGRESS` | Accepted | Refused → void submission |
| **`EXPIRED`** | **Accepted** — the scheduler simply got there first | Refused → void submission |
| `SUBMITTED` / `CANCELLED` / `VOIDED` | Refused → void submission | Refused |

An operator who finishes a round at 17:55 against an 18:00 deadline and only reaches signal at
19:30 keeps their work. Without this, every round walked out of coverage would be lost to the
scheduler, which is the whole scenario this system exists for. It is also why the scheduler's
own expiry (`OPEN_FOR_EXPIRY_STATUSES`) deliberately excludes `SUBMITTED` — expiry and
completion race, and completion must win when it was genuinely first.

The device mirrors the same rule so it does not queue work the server is certain to refuse —
`isLogSheetExpiredForSync` in the PWA — and re-queues a completion it can prove was in time if
one was rejected while the two disagreed. See the PWA's `docs/sync.md`.

## The payload is never dropped

A tablet may still sync a round the server will not accept: the sheet was cancelled, somebody
else already submitted it, or the work genuinely finished late.

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
| POST | `/log-sheets/{id}/draft` | Save a draft — sets `draft_saved_at` with `draft_source = WEB`. From the fill page this now carries the sheet's notes only |
| POST | `/log-sheets/{id}/entries/{entryId}/draft` | **One asset's readings**, from the fill page's dialog. Answers with that asset's read-only summary re-rendered. `entryId` is checked against the sheet — the path decides what is written, never the body |
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
| POST | `/log-sheets/{id}/approve` | `SUBMITTED` → `APPROVED`; the supervisor's sign-off |
| POST | `/log-sheets/{id}/unapprove` | `APPROVED` → `SUBMITTED`; withdraws it |
| POST | `/log-sheets/{id}/reopen` | Reopen a submitted sheet with a new deadline |
| POST | `/log-sheets/{id}/admin-reopen` | Admin override reopen |

### Attachments and void submissions

| Method | Path | Purpose |
|---|---|---|
| POST | `/log-sheets/{id}/attachments` | Upload from the web fill form |
| GET | `/log-sheets/{id}/attachments/{attachmentId}` | Stream a file |
| POST | `/log-sheets/{id}/attachments/{attachmentId}/delete` | Delete. The attachment must be **on the sheet in the path** — the two ids otherwise answer different questions ([security.md](security.md#seeing-a-log-sheet-and-changing-it-are-two-different-permissions)) |
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
| **POST** | **`/api/log-sheets/batch`** | **The sync endpoint** — completed rounds, one call per pass |
| **POST** | **`/api/log-sheets/progress`** | **Partial values from a round still being walked** — never completes anything (§3.5) |

Supporting endpoints:

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/bootstrap` | Master data + settings for the device |
| GET | `/api/asset-entries/nfc/{nfcTagId}` | Resolve a scanned tag |
| POST | `/api/asset-entries/{id}/nfc-serial` | Record a chip's hardware serial |
| POST | `/api/attachments` | Upload media (multipart). Requires the sheet be **writable** — the assignee, or `CAP:LOGSHEET_COMPLETE_WEB_ANY` — not merely visible |
| GET | `/api/attachments/{id}` | Download |
| DELETE | `/api/attachments/{id}` | Delete — writable sheet only, and never on an `APPROVED` round |
| POST | `/api/nfc-fault-reports/batch` | Sync fault reports |
| POST | `/api/auth/login` | Obtain a JWT |
| GET | `/api/health` | Liveness |

### The attachment endpoints

**An approved round's evidence cannot be removed.** `DELETE /api/attachments/{id}` answers `409`
once the sheet reaches `APPROVED`, with «این لاگ‌شیت تأیید شده است و پیوست‌های آن قابل حذف نیستند».
Readings were already protected — `requireOpenSheetForWeb` refuses every terminal status — while
attachments were checked only for *visibility*, so on one approved sheet the panel refused a
reading and accepted the removal of a photograph in the same breath. Only `APPROVED`, and only
deletion: `SUBMITTED` stays open because a delivered round is still under review and correcting it
before sign-off is what `reopen` exists for, and uploads stay open on every status because a
tablet offline at approval time still holds photographs from the round itself. The way out is
`unapprove`, which is recorded.

The tablet handles that `409` as "not this one, not yet": `drainPendingDeletes` keeps the row
queued and moves on to the next, so one frozen sheet cannot stall every other deletion behind it.

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

### The progress endpoint

`POST /api/log-sheets/progress` is its own endpoint rather than a mode of `/batch`, and its own
permission rather than a reuse. The two answer different questions — one delivers finished work,
the other publishes unfinished work — and their outcome vocabularies differ because the device has
to act on them differently. A refused progress push must never touch a row's local `status` or
`syncStatus`; those belong to the submit path, and sharing one response type is how a "the server
said no" branch ends up marking real, undelivered work as failed.

Outcomes: `SAVED`, `NO_CHANGE` (nothing to report), `SUPERSEDED`, `CANCELLED`, `EXPIRED`,
`VALIDATION_ERROR`, `ERROR`. Note the absence of `DUPLICATE` — see §3.5. Same one-transaction,
`app.sync.batch-max-items` cap as the batch endpoint.

### The batch endpoint

`POST /api/log-sheets/batch` runs **synchronously in one transaction**, capped by
`app.sync.batch-max-items` (default 500). Synchronous on purpose: the device needs to know in
the response which items were accepted so it can clear its queue. Every item carries a
`client_action_id`, so a retry after a timeout is safe.

---

## Third-party integration API — `/integration/v1/log-sheets`

A **read-only** surface for external systems, authenticated by an `X-API-Key` header on its own
filter chain. No user, no session, no JWT, no role — see
[security.md §7](security.md#7-the-fourth-authentication-surface--integration-api-keys) for the
whole story.

| Method | Path | Returns |
|---|---|---|
| `GET` | `/integration/v1/log-sheets?from=&to=&statuses=&unitId=&templateId=&page=&size=` | one page of finished sheets |
| `GET` | `/integration/v1/log-sheets/{id}` | one finished sheet in full |

**Only finished sheets ever leave through here.** `SUBMITTED`, `APPROVED`, `VOIDED`, `EXPIRED`,
`CANCELLED`
— and that list is a literal inside `LogSheetRepository.findExposableToIntegration`, not merely
validated by the caller, so a `PENDING` / `ASSIGNED` / `IN_PROGRESS` sheet cannot be returned by
any route into that method. Asking for one is a **400**, not an empty page: silently dropping it
would let a caller conclude that no round is ever in progress.

**Which timestamp the date range matches on depends on the status**, because "when did this
finish" is a different column per state and each is written exactly once:

| Status | Windowed on |
|---|---|
| `SUBMITTED`, `VOIDED` | `completed_at` — the device-authoritative completion time |
| `EXPIRED` | `expired_at` |
| `CANCELLED` | `cancelled_at` |

Every row echoes the one that matched as `finalizedAt`, so a consumer never has to reimplement
the rule. Backed by `idx_log_sheets_status_finalized_at` ([schema.md](schema.md#log_sheets)).

**The range is half-open, `[from, to)`.** A closed range forces every caller to answer "does
`to=2026-08-31` include the 31st?", and the two reasonable answers differ by a day; worse,
consecutive polls then either double-count the boundary instant or lose it. Half-open makes
`from=2026-08-01&to=2026-09-01` exactly August, and makes yesterday's `to` usable verbatim as
today's `from` with no overlap and no gap — which is what a polling integration actually does.

**Defaults:** `statuses` omitted means **completed rounds — `SUBMITTED` *and* `APPROVED`**
(`LogSheetStatus.COMPLETED_STATUSES`). "Completed log sheets" is what the integration exists to
publish, and a default that quietly included voided rounds would have an external system importing
readings this plant has explicitly invalidated.

`APPROVED` belonging in that default is not a detail. Approval is a review step laid on top of
completion, so a round a supervisor has accepted is *more* trustworthy than one nobody has looked
at — not a different kind of outcome. Had it been left out, every consumer on the default would
have silently stopped receiving rounds the day approval was switched on, with no error anywhere
and nothing to distinguish it from a quiet plant. A consumer that wants only unreviewed rounds, or
only approved ones, names the status. `unitId` and
`templateId` omitted mean **no restriction**: the whole plant. `size` defaults to 50 and is
clamped to 200, with the effective value echoed in the response so a caller can see it happened.
Both numbers are configuration — `app.integration.default-page-size` and
`app.integration.max-page-size` — read once at startup. The configured maximum is itself capped at
1,000 and clamped with a WARN if set higher, so a mistyped properties value cannot remove the
bound.

**An expiry-finalised draft is genuinely `SUBMITTED`** (see [jobs.md](jobs.md#log-sheet-expiry))
and is returned as such. It is not flagged separately: it was completed, its readings are real,
and its `completed_at` is its deadline — which is exactly what the row says.

The detail response carries the sheet, the parameter schema frozen at generation, every asset,
and the values recorded against each — with `maxSeverity` and `breachedFields` per asset, which
is the part an external maintenance system will actually act on. **Attachments are announced,
never served**: id, kind, mime type, size and duration, with no bytes and no download endpoint.

Timestamps are ISO-8601 UTC on this surface only; everywhere else in this system they are epoch
millis, and the conversion happens at the boundary so an integrator never has to know that.

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
7. Test with a **scoped** user, not only an admin — and, if the action is something the sheet's
   own assignee performs, with an assignee who is no longer in the sheet's unit. That case has
   its own rule (§3, *An operator removed from their unit while offline*) and is the one nobody
   thinks to try.
8. Update this file and [schema.md](schema.md) in the same commit.

## Related

- **[schema.md](schema.md)** — the tables
- **[hierarchy.md](hierarchy.md)** — how scope decides what lands on a round
- **[jobs.md](jobs.md)** — generation and expiry
- **[reports.md](reports.md)** — what is measured from all this
- **[security.md](security.md)** — who may perform each transition, and the service-layer rules behind the endpoint permissions
