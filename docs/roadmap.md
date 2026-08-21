# Roadmap — decisions deferred, with the facts they rest on

> **No design in this file is implemented.** Every other document under `docs/` describes the
> system as it is; this one describes work that has been thought through and not done. Keep the
> separation — a reader who cannot tell the two apart will trust a design as if it were
> behaviour, which is exactly the failure the documentation rule in `CLAUDE.md` exists to
> prevent.
>
> When one of these is built, move the content into the reference documents it belongs to
> (`hierarchy.md`, `security.md`, `log-sheets.md`, `schema.md`) and delete the section here.

Sections come in two kinds, and the difference matters when you read one:

- **A feature that does not exist** (§1, §2, §3, §4) — nothing in the description is running.
- **Behaviour that does exist, with a decision about it deliberately deferred** (§5, §6). The
  "what happens today" parts of these are true and are also documented in the reference files;
  what is unbuilt is the *change*. They are here so that somebody meeting the behaviour in the
  field can tell "known and decided against for now" from "nobody has noticed this yet".

Each section records the **facts about the current system** that the decision depends on. Those
facts were expensive to establish and are the part most worth keeping: a design can be
re-argued cheaply, a constraint discovered by reading five files cannot.

---

# 1. Sub-assets — a component that belongs to another asset

*Raised as: "a computer is an asset, and its GPU / RAM / disk also need to be defined."*

## What the model does today

| Fact | Where |
|---|---|
| `asset_entries` has **no `parent_id`** — an asset is a leaf, and its parent is always a sub-function | [schema.md](schema.md) |
| `asset_entries.sub_function_id` is `NOT NULL` — every asset sits in a position | [hierarchy.md](hierarchy.md#2-the-physical-tree-in-detail) |
| `ux_asset_entries_active_sub_function` is **partial on `WHERE active`** — one *installed* asset per position, unlimited retired ones | [hierarchy.md](hierarchy.md#4-nfc-identity-and-asset-replacement) |
| `sub_functions` **self-nest** via `parent_id`, and carry every ancestor id denormalised | [hierarchy.md](hierarchy.md#ancestry-is-denormalised-on-purpose) |
| `findByClassIdInSubFunctionScope` is a **recursive CTE** over `sub_functions.parent_id` | `AssetEntryRepository` |
| The NFC tag belongs to the **position**, not the equipment, and `ux_sub_functions_tag_lower` makes it unique plant-wide | [hierarchy.md](hierarchy.md#4-nfc-identity-and-asset-replacement) |
| The PWA matcher resolves **one entry per scan** — `entries.find(e => e.nfcTagId === needle)` | PWA `services/nfc/matchLogSheetEntry.ts` |

## Where this sits in the standards

The existing model is already the standard one: a sub-function is a **functional location**
(ISO 14224 / IEC 81346, `FLOC` in SAP PM) and an asset is the **equipment installed in it**.
In ISO 14224's taxonomy a component (*maintainable item*) is its own item with its own history,
not an attribute of its parent — so **components should be assets, not fields**. A field is the
right shape only for a value that is read; anything with a life, a replacement and a history of
its own is an asset.

## The question that decides the design

**Does the component travel with the machine, or stay with the position?**

- *Stays* — "the RAM in slot 1 of PC-01" is what gets inspected → **Option A**.
- *Travels* — a serialised module moves to another machine and its history goes with it, which
  is precisely why SAP separates Equipment from Functional Location → **Option B**.

## Option A — components as child sub-functions (no migration)

```
Sub Fn  PC-01              (tag: PC-01)  → Asset: Dell OptiPlex  [class: Computer]
  ├─ Sub Fn  PC-01-GPU                   → Asset: RTX 4060       [class: GPU]
  ├─ Sub Fn  PC-01-RAM-1                 → Asset: 16GB DDR5      [class: RAM]
  └─ Sub Fn  PC-01-SSD                   → Asset: 1TB NVMe       [class: SSD]
```

Works with what already exists, unchanged: ancestry is filled automatically, the scope CTE is
recursive so a template scoped to `PC-01` picks up every component beneath it, component
replacement is ordinary asset replacement, unit scope and every report keep working.

Costs: a much larger and deeper sub-function tree; the Excel import needs to express nesting;
and the NFC problem below.

## Option B — `parent_asset_id` on `asset_entries`

Closer to SAP's *superior equipment*. What has to change:

| Area | Change |
|---|---|
| Migration | `parent_asset_id` + index, and a decision on ancestry (repeat the denormalised pattern, or a recursive CTE) |
| `AssetHierarchyService` | The only class allowed to write placement — must cascade and reject parent cycles |
| `ux_asset_entries_active_sub_function` | Must be revisited: several active components under one sub-function becomes meaningful |
| Scope queries | Every `findByClassIn*Scope` has to decide whether components are returned |
| Log sheet generation | Does one sheet carry the machine *and* its components? Both `EXPLICIT` and `SCOPED` |
| Deactivation | What happens to components when the parent is retired |
| PWA | Bundle, asset list, the per-asset completion count, the fill UI |
| Docs | `hierarchy.md`, `schema.md`, `log-sheets.md` |

## The real blocker, in both options: NFC

The fill flow is built on **one scan = one asset**. Tags are unique plant-wide, and the matcher
returns the first entry whose tag matches — so components cannot share their parent's tag, and
putting eight chips on one cabinet is not a real option.

Any component model has to answer this first. Three ways out, cheapest first:

1. **Scan the parent, fill a group.** One scan opens a form covering the machine and its
   components. Changes the matcher (return a set, not one entry) and the fill UI — **not** the
   data model.
2. **A tag per component.** No code change; an operational burden.
3. **Untagged components via manual entry.** Possible today, but gated behind a permission or an
   NFC fault report, so it is a fallback rather than a normal path.

## Suggested order

1. Model one machine with **Option A** and no code at all. If it holds, stop.
2. If "one scan, many rows" is needed, change the matcher and the fill UI only.
3. Reach for **Option B** only when a serialised component genuinely moves between machines and
   has to carry its history along.

---

# 2. Request rate limiting

*Raised by a security review. **Deferred deliberately, not overlooked.***

## What exists today

| Fact | Where |
|---|---|
| Failed **logins** are throttled per username, with a lockout | `LoginAttemptService`, `app.auth.login-attempt.*` |
| That throttle sits in `AppAuthenticationProvider`, so it covers **both** chains — the web panel and `/api/auth/login` | `AppAuthenticationProvider` |
| The integration API bounds **response size** (`app.integration.max-page-size`, hard ceiling 1,000) | [log-sheets.md](log-sheets.md#third-party-integration-api--integrationv1log-sheets) |
| The mobile batch bounds **items per request** (`app.sync.batch-max-items`, 500) | `LogSheetService.submitBatch` |
| An API key's `last_used_at` write is throttled so a per-minute poller does not cause a write per request | `ApiKeyService.LAST_USED_THROTTLE_MS` |
| **Nothing limits requests per second, per key, per user or per IP** | — |

## Why it is acceptable now

Every reachable surface is already bounded in the dimension that costs the server: a request
cannot ask for an unbounded page, submit an unbounded batch, or brute-force a password. What is
missing is a bound on *frequency*, and the deployment shape supplies that instead — a plant LAN,
no route from the internet, integration keys issued one per named consumer and revocable
individually.

## What would make it urgent

Any one of these, and this stops being a reasonable trade:

- the API becomes reachable from outside the plant network, or from a segment holding devices
  nobody administers;
- an integration is given to a third party whose polling interval you do not control;
- the tablet fleet grows to the point where a stuck client retrying in a tight loop is
  indistinguishable from normal traffic (see § 3 below — the two problems meet there).

## Where it would go

Not in the application. A limit belongs at the edge that already terminates TLS, because that is
the layer that can drop a request before it costs a thread or a database connection:

```nginx
limit_req_zone $binary_remote_addr zone=api:10m rate=20r/s;
```

Inside the application, the one place worth a bespoke limit is the integration chain, keyed on
`api_keys.key_id` rather than on IP — an integration is identified by its key, and several may
share one host.

---

# 3. The cost of `/api/log-sheets/inbox` per sync tick

*Raised by a performance review. **A scaling ceiling, not a defect.***

## What the endpoint does today

```java
List<LogSheetBundleDto> assigned = logSheetAccessService.findAssignedTo(userId).stream()
        .map(bundleService::buildFullBundle)     // ← the whole bundle, per sheet
        .toList();
```

| Fact | Consequence |
|---|---|
| `buildFullBundle` runs entries + filler names + context + fault reports + attachments per sheet | roughly a dozen queries **per assigned sheet** |
| `buildContext` walks the location tree | level-wise since the N+1 fix, but still several queries |
| The PWA calls this on **every** sync tick, default `syncIntervalMs` 30 s | one full rebuild per tablet per tick |
| There is no `ETag` / `If-None-Match`, so an unchanged inbox is re-serialised in full | the response is rebuilt whether or not anything moved |
| `idx_log_sheets_assignee_user_id` exists, so finding the sheets is cheap | the cost is the bundles, not the lookup |

**This is deliberate, and the reason is offline-first.** The inbox is not a listing — it is the
pre-provisioning step that puts everything a tablet needs to work without a network into
IndexedDB. Trimming it to a summary would mean a tablet that walks out of coverage holding a list
of sheets it cannot open.

## Where the ceiling is

With tens of tablets and one to three assigned sheets each, this is a fraction of a second per
tick against a 10-connection pool — comfortably inside budget. The arithmetic to redo before a
larger deployment is: **tablets × assigned sheets × ~12 queries ÷ sync interval**. Watch for it
when any of these change:

- the fleet grows past roughly a hundred devices;
- operators routinely hold many sheets at once rather than one or two;
- `syncIntervalMs` is lowered to make the app feel more responsive — this multiplies everything
  above, and is the change most likely to be made for an unrelated reason.

## The options, cheapest first

1. **Conditional GET.** An `ETag` over the assigned set's `updated_at` values turns a quiet tick
   into a 304 and one cheap query. Changes no data shape, and the PWA already stores an
   `inboxSnapshot` it could fall back to. Most of the win for the least risk.
2. **Cache the context, not the sheet.** Sheets from one template share almost all of their
   context; today each rebuilds it. A short-lived per-template cache would cut the per-sheet
   queries without changing the response.
3. **Split the inbox.** A light list plus bundles fetched on demand. **This one changes the
   offline guarantee** — a sheet is only openable offline once its bundle has been fetched — so
   it needs the fill flow and the pre-provisioning tests reconsidered together, not a patch.

Take them in that order. Option 1 is invisible to the client; option 3 is a change to what
"offline-first" means here, and should not be reached for as a performance fix.

---

# 4. General optimistic concurrency on a mobile submit

**Not built, and deliberately not.** The server today refuses exactly one class of stale write.
This records what the general form would be, and the conditions under which it stops being
optional.

## What exists today

`wouldBlankUnseenAnswer` (`LogSheetService`) refuses a submitted entry that would **erase** a
stored answer when the device's echoed `(created_at, updated_at)` pair does not match the entry's.
Equality, not ordering: the device echoes back whatever the last bundle gave it, so a match means
"this device is up to date" and no two clocks are ever compared. See
[log-sheets.md](log-sheets.md#who-wins-when-two-people-have-touched-the-same-sheet).

That covers the destructive case — a blank arriving over a real reading. It does not cover a
**value** arriving over a newer value: if the device holds `6`, a supervisor changes the entry to
`8` in the browser, and the device then submits `6`, the server accepts it.

## Why that is acceptable now

Because after the `locallyEditedAt` fix, a device only submits a value it holds locally when
somebody actually typed that value on it. So the surviving case is a real edit-versus-edit
conflict between two people, which the system already resolves as **last-writer-wins per entry**,
knowingly and documented. The thing that used to make this dangerous was not the absence of a
version check — it was the client treating *received* values as its own work, which meant an
ordinary tablet resubmitted readings nobody had touched. That is fixed on the client, where it
belonged, and `serverCorrectionWins.test.ts` holds it there.

There is also a cost to getting this wrong in the other direction. A refusal that fires on a
legitimate submit is worse than a lost edit: the tablet is offline-first, a rejected batch is work
the operator has already walked away from, and the only recoverable outcome is one they cannot see
happening.

## What would make it urgent

- **Two people editing the same sheet at the same time becomes normal**, rather than the
  reopen-and-hand-back sequence it is today. The current rule assumes the sheet has one owner at
  a time.
- **A device is allowed to hold a sheet across a reassignment.** Today `shouldPreserveLocalFormData`
  makes the device give up its copy the moment the server assignee differs, which removes most of
  the window.
- **Sheets get long-lived drafts.** The wider the gap between a bundle fetch and its submit, the
  more likely the base has moved underneath it.

## What it would look like

The mechanism is already there and would only be generalised: the entry's `(created_at, updated_at)`
pair is the version, the device already echoes it, and `wouldBlankUnseenAnswer` already compares it
for equality. The change is to apply that comparison to **every** differing field rather than only
to a blanking one, and — the part that is actually the work — to decide what the device does with a
refusal.

Three things would have to be designed together, and none of them is server-side:

1. **The response.** `LogSheetSubmitResult` would need a rejection kind that means "your base is
   stale", distinct from the existing ones, carrying the server's current values.
2. **What the operator sees.** A silent refusal is the failure mode this whole area keeps
   producing. The device would have to show the conflict and let a person choose, which is a screen
   that does not exist.
3. **Whether a partial submit is allowed.** A batch is a sheet; refusing one entry of forty and
   accepting the rest is a different contract from today's all-or-nothing outcome per sheet.

Do **not** implement the comparison without those three. A server that starts refusing entries
into a client that has no way to report it is strictly worse than last-writer-wins: the work is
lost either way, and nobody is told.

---

# 5. A reading whose field no longer exists

**Not a defect, and not obviously right either.** Recorded because somebody will meet it in a
report and have to decide whether it is a bug.

## What happens today

A class's field list changes: a parameter is added, renamed or dropped. Readings already stored
under the old key stay in `log_sheet_entries.form_data` — the key is whatever the field was
called when the operator entered it.

`/reports/asset-parameters` iterates that stored `form_data`, so **the value still appears in the
table**. The current `field_definitions` rows supply only the label, the unit and the
warning/danger thresholds — and a deleted field has none, so the row falls back to the raw key
with no unit and no colouring. It also disappears from the parameter dropdown and cannot be
charted. Full behaviour table in [reports.md §7](reports.md).

## Why it is not simply a bug

The report is showing the truth: that reading was taken, and this is what it was called. Hiding
it would be worse, and inventing a label for a definition that no longer exists would be a guess.

## The part that is genuinely inconsistent

**The log sheet and the report disagree about the same reading.** A sheet carries
`field_definitions_snapshot`, frozen at generation, so opening the round still shows «دمای ورودی»
with its unit and its thresholds. The report, reading live definitions, shows `inlet_temp` with
neither. Same value, two presentations, and nothing tells the reader why.

Related: **deleting a field is a hard delete.** `AssetClassWebController.deleteField` calls
`fieldDefinitionRepository.delete(...)`. The `field_definitions.deleted` column exists and this
path does not use it — so there is no row left to read a historical label from.

## The options, cheapest first

1. **Label from the sheet's snapshot.** Each reading row already knows which log sheet it came
   from, and that sheet carries the definition as it was. Resolving the label, unit and thresholds
   from `field_definitions_snapshot` — falling back to the live definition, then to the raw key —
   makes the report agree with the sheet and needs no schema change. **The obvious first move.**
2. **Soft-delete the field instead.** Set `deleted = true` rather than removing the row, and read
   labels from it. Simpler to implement than option 1 and weaker: it recovers the *last* label,
   not the one in force when the reading was taken, so a renamed field still misreports history.
3. **Mark retired parameters in the UI.** Whatever the label source, a reading under a field the
   class no longer has should probably say so — a chip, a muted row — rather than looking like a
   current parameter. Worth doing alongside either option above.

## What would make it urgent

- Field definitions start being edited routinely rather than set up once.
- A parameter is **renamed** — the case where the report is not just plain-looking but genuinely
  misleading, because the same physical measurement appears under two names with no link between
  them.
- Somebody exports the parameter report for an audit and the column headers do not match the log
  sheets the auditor is holding.

---

# 6. An operator removed from a unit while offline

**Traced, not hypothetical.** The behaviour is safe — nothing is lost — but it is inconsistent,
and the inconsistency is invisible to everyone involved.

## The sequence

1. An operator claims a sheet; they are the assignee.
2. They go offline and fill it — readings plus photos.
3. While they are away, an administrator removes them from the operational unit
   (the `unit_operators` row is deleted).
4. They come back online and the tablet syncs.

## What happens, and why the two halves differ

| | Result | The check that decides it |
|---|---|---|
| **Readings** | ✅ accepted, sheet goes `SUBMITTED` | `LogSheetService.submitOne` asks only whether the caller **is the assignee** |
| **Attachments** | ❌ refused with 403 | `AttachmentService` starts every method with `logSheetAccessService.requireVisibleLogSheet(...)`, which applies **unit scope** |

Removing somebody from a unit does not touch `log_sheets.assignee_user_id`, and it does not touch
their roles — so they still authenticate and still hold `POST:/api/log-sheets/batch`. The submit
path has no unit check at all. `requireVisibleLogSheet` does: `getAccessibleUnitIds` no longer
contains that unit, so every attachment call throws.

Note also that `executeSync()` is **push only** — the inbox pull is separate — so the sheet
vanishing from their inbox does not stop the submission.

## Why nothing is lost

`attachmentSync.isPermanentFailure` deliberately does **not** treat 403 as permanent:

```ts
if (err.status === 403) return false   // retryable — do not park
```

The files stay queued and retry on every tick, so **putting the operator back in the unit uploads
them with no manual step.** The comment there records why: parking a 403 once cost a whole round's
photographs on a shared tablet.

## What is actually wrong with it

One operation from the operator's point of view — "send my work" — is judged by two different
rules. The result is a sheet that looks complete and is missing its photographic evidence, with
no warning on either side: the panel shows a submitted round, and the tablet shows a queue that
never drains.

## The options

1. **Make attachments follow the submit rule.** If you are the sheet's assignee, you may upload
   its attachments — regardless of current unit membership. Most consistent with "accept work that
   was actually done", and the smaller change: it is the guard in `AttachmentService`, not the
   scope service, that would take an assignee branch. **Preferred.**
2. **Make submit follow the attachment rule.** Rejects the readings too. Throws away real work
   because somebody's paperwork changed — worse.
3. **Leave it and surface it.** If the mismatch is rare, at least make it visible: a sheet whose
   attachments never arrived should say so on the log sheet page, and the tablet should show why
   its queue is stuck.

Option 1 plus the visibility half of option 3 is the combination worth building.

## What would make it urgent

- Staff move between units routinely rather than exceptionally.
- Photographs become evidence somebody audits, rather than a convenience.
- A round is completed and its attachments are still queued when the tablet is wiped or reassigned
  — the one path where the retry-forever design does not save them.
