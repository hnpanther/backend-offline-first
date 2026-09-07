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

- **A feature that does not exist** (§1, §2, §3, §4, §9, §10, §11, §12, §13) — nothing in the description is running.
- **Behaviour that does exist, with a decision about it deliberately deferred** (§5, §7, §8). The
  "what happens today" parts of these are true and are also documented in the reference files;
  what is unbuilt is the *change*. They are here so that somebody meeting the behaviour in the
  field can tell "known and decided against for now" from "nobody has noticed this yet".
- **Built since** (§6) — kept only as a pointer to where the answer now lives, and deleted the
  next time this file is tidied.

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

## What one refused request actually costs

Measured against the code rather than estimated, because the shape of it decides how urgent this
is. **A request with no `X-API-Key` header at all is the cheapest thing an attacker can send and
still costs this server a write.** `ApiKeyAuthenticator.authenticate` returns at its first line
for a missing key and at its second for a malformed one — neither reads the database — but
`IntegrationApiKeyFilter` then records a usage row and logs a WARN regardless. That is deliberate
and correct (the refusals are the rows worth having; a run of `INVALID_KEY` from one address is
the only evidence anybody gets that keys are being guessed), and it is also the asymmetry: zero
cost to the sender, one INSERT and one log line to the receiver.

Under sustained load the write does not simply queue forever. `ApiKeyUsageWriteService` runs on
`auditExecutor`, whose `CallerRunsPolicy` is load-bearing by design: once the queue is full the
INSERT is performed **on the request thread**. For an audit trail that is the right failure —
"slower" beats "missing" — but it means a flood degrades request latency rather than being
absorbed silently.

Three things bound it today, and all three are properties of the deployment rather than the code:

| Bound | Where | What it does not cover |
|---|---|---|
| Nightly purge at 03:00 | `ApiKeyUsageRetentionService`, cron `0 0 3 * * *` | A flood **within** one day is unbounded until the purge runs |
| Queue capacity 2,000 before `CallerRunsPolicy` engages | `app.audit.async.queue-capacity` | Once engaged, every further request pays the insert inline |
| **nginx does not proxy `/integration/**` at all** — only `/api/` is forwarded | the PWA's `docs/deployment.md` | Reaching it needs direct access to port 8081 |

The third is the one carrying most of the weight, and it is the one most easily lost: adding a
`location /integration/` block to nginx, or exposing 8081, removes it without anything failing.
**If that changes, add the limit in the same commit** — the `limit_req_zone` below is the whole
fix and it needs no application change.

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

# 6. An operator removed from a unit while offline — **built**

**Resolved.** One action from the operator's point of view — "deliver the work I did" — used to be
judged by two different rules, and the deferred question was which rule should win. Option 1 was
recorded here as preferred: make everything follow the submit rule. That is what shipped.

`LogSheetAccessService.canView` now reads *unit scope **or** being that row's current assignee*, so
the readings, the attachments, the NFC fault reports, the bundle refresh and the sheet's own page
in the panel all behave the same way. The scope of the problem turned out to be wider than this
entry recorded: the bundle and entry endpoints and the panel's detail page were affected too, not
only attachments.

See [security.md §1](security.md#1-the-three-layers) for the rule and its three bounds, and
[log-sheets.md §3](log-sheets.md) for the before/after table. Regression:
`AttachmentApiIntegrationTest` § *The assignee's own work, after they leave the unit*.

The visibility half of option 3 was not built and is not needed: there is no longer a mismatch to
surface.

---

# 7. Field-level merging of two people's edits to one asset

**Unchanged by progress sync, and worth restating now that it is easier to hit.**

Merging is per **entry**, never per field. Two people editing different fields of the same asset is
last-writer-wins, knowingly — field-level merging would settle that case too, and is a much larger
change for a much rarer conflict.

## What changed around it

Progress sync makes the *rare* case slightly less rare, and the *destructive* case less likely:

- A round now reports its readings as they are taken, so a supervisor correcting an asset in the
  browser and the operator still holding the sheet are working from the same values far more of
  the time. The device clears its `locallyEditedAt` marker when the server accepts a report, so
  the supervisor's later correction wins the next merge instead of being overwritten at submit —
  which it would have been before.
- What is genuinely lost when last-writer-wins fires is now **recoverable**: the replaced value is
  in `log_sheet_entry_revisions` and rendered on the sheet's page. It is not a merge, but it is no
  longer a silent loss.

## What would make it urgent

- Two people routinely filling different fields of the same asset within one round.
- A complaint that a value "changed back", where the revision panel shows the two writes
  interleaving rather than one person simply being later.

---

# 8. The web fill dialog — one thing left open (and three done)

*Raised by a review of the per-asset fill dialog. **What is left is deferred deliberately, with
the facts.** Three of the five findings are built: §8a, §8b — a save that reported success having
written nothing — and §8d. A fifth finding, attachments using visibility as a write rule, was a
live security hole and is **fixed**; see
[security.md](security.md#seeing-a-log-sheet-and-changing-it-are-two-different-permissions).*

## 8a. An attachment and its `form_data` reference can disagree — **built**

**Resolved**, in steps (1) and (2) of the order recorded here: the web fill page's attachment
endpoints now write the reference in the same transaction as the file, and the dialog's «انصراف»
is «بستن» with a line saying files save immediately. **The mobile API deliberately keeps the old
behaviour** — applying the same reconciliation there removes every row `enforceCount` could
reclaim and turns a tablet's replacement capture into a 409. Both directions are closed at the source — an abandoned upload is
named by the reading, and a deletion removes the reference as it removes the file — so the orphan
row this entry described is no longer produced. See
[log-sheets.md §3](log-sheets.md#a-file-and-the-reading-that-names-it-are-written-together) for the
rule and what it deliberately does not touch, and AGENTS.md gotcha #123.

**Step (3) was investigated and deliberately not built, and the reason is worth keeping.** The
proposal was to refuse completion when a referenced attachment does not exist. It would refuse
sheets that are perfectly normal: a tablet pushes the sheet **first** and uploads its attachments
afterwards — the upload queue is gated on the sheet having a server id — so a reading naming ids
the server has no rows for is an ordinary intermediate state, not corruption. The same fact is why
the reconciliation adopts and never drops. `enforceCount` already reasons from it, in the comment
beginning *"A reference with no row is ambiguous"*.

What could safely be checked is narrower — an id that exists and belongs to a **different** sheet,
which is never legitimate — and it is not built either: after (2) the only route to one is a
hand-made request, and the download endpoint already refuses it via
`requireAttachmentBelongsToSheet`.

**Step (4) is still open**, and much less urgent than it was. Orphans stop being created, so what
remains is a finite backlog from before this change. Two facts for whoever picks it up: re-uploading
a file whose reference went missing repairs it (the idempotent branch reconciles too), and any
report must skip rows protected by `log_sheet_entry_revisions` or a void submission — see
`protectedAttachmentIds`. **Start it as a report, not a job:** deleting evidence automatically is
the wrong default, and nobody has yet seen the numbers.

## 8b. Clearing every multiselect on an asset whose fields are all multiselects — **built**

**Resolved**, in the shape recorded here: each dialog posts `fd_present_<entryId>=1` and
`parseEntryValues` reads it as "this entry was submitted", seeding an empty map. "Everything
cleared" is now a state the server is told about rather than one it has to infer from an absence
that already meant something else.

The third state turned out to be the part worth writing down, and it is not in the original entry:
**no marker still means "not submitted"**, and that is load-bearing rather than legacy — a dialog
names one asset out of possibly hundreds, so reading a missing marker as "cleared" would blank
every other asset on the sheet.

Because the marker is read in the shared parser, `/draft` and `/complete` honour it identically.
See [log-sheets.md §3](log-sheets.md#every-dialog-says-which-asset-it-is-submitting) for the rule
and its three states, and AGENTS.md gotcha #122 for how the defect concealed itself. Regression:
`WebFillClearedFieldsIntegrationTest` — twelve cases, five of which fail if the marker handling is
removed.

## 8c. Each dialog save reads the whole sheet three times

One `POST /log-sheets/{id}/entries/{entryId}/draft` calls `findByLogSheetId` **three times** — the
ownership check, the loop inside `applyWebEntryValues`, and `groupByClass` for the re-render — and
`findForLogSheet` once, which loads every attachment on the sheet to render one card.

Filling a sheet asset by asset is therefore *n* saves each touching *3n* entry rows. At the 300
ceiling (§ `LogSheetSizeLimits`) that is the shape of an O(n²) walk. Nothing is wrong at 47.

**Fix shape.** A `findByIdAndLogSheetId(entryId, sheetId)` for the ownership check, a service
method that loads only the entry being written, and attachment/definition lookups scoped to that
one asset and class. No behaviour changes — only the reach of the queries.

## 8d. Prose that still describes the old form — **done**

The web fill page stopped posting every entry in one submission, and several places still say it
does. Most are comments, but **two are load-bearing**: `LogSheetSizeLimits` and the matching block
in `application.properties` justify the 300-asset ceiling, and the *first* reason both give is

> the web fill page … renders every entry in ONE form and resubmits all of them on every save:
> past Tomcat's `max-parameter-count` the extra parameters are dropped SILENTLY

That failure mode no longer exists — a dialog posts one asset's fields, and the parameter count no
longer grows with the sheet. The other two reasons (a tablet rewriting its whole entries array,
and a round being one operator's claim) still hold. Left as it is, the document **argues for the
ceiling from a constraint that was removed**, which is exactly the way somebody talks themselves
into raising it.

**Corrected**, in `LogSheetSizeLimits`, `application.properties`, `LogSheetService`,
`docs/log-sheets.md` (both places), `README.md`, and the two test comments in
`LogSheetEntryRevisionIntegrationTest` and `ReopenedSheetSupervisorEntriesIntegrationTest`.

The removed reason is **recorded rather than deleted** everywhere it appeared. Deleting it would
leave the 300 looking like a number somebody picked; saying which of its three reasons has gone is
what lets the next person judge whether the remaining two still carry it. Nothing was configured
for `max-parameter-count` in the first place — the comments referred to Tomcat's default — so the
values are unchanged.

`docs/schema.md:18` was deliberately left alone: it describes what V3 repaired historically, and
that account is still accurate.

---

# 9. The last recorded value, while the reading is being taken

*Requested: "when somebody fills a parameter, let them see what that asset last read."*

## What exists today

| Fact | Where |
|---|---|
| Readings live in `log_sheet_entries.form_data`, keyed by field key, one row per (sheet, asset) | [schema.md](schema.md) |
| `idx_log_sheet_entries_asset_read` is **`(asset_id) WHERE max_severity IS NOT NULL`** — a partial index on exactly "entries of this asset that carry a reading" | V1 |
| A sheet is raised with one entry per asset whether or not anybody reaches it, so unfilled rows are the majority — 128 of 133 on the first live dataset | that index's own comment |
| `/reports/asset-parameters` already walks one asset's stored `form_data` over time | [reports.md](reports.md) |
| Nothing shows a previous reading **while filling**, on either surface | — |

**The index this needs already exists.** No table and no column: the value is derived.

## The question that decides the design

**The last *entry*, or the last value of *each parameter*?**

An operator who filled three parameters of seven leaves the other four empty. "Last entry" then
shows four blanks for parameters that had values a month ago; "last value per parameter" is what
somebody actually wants, and costs a `jsonb_each` expansion and a `DISTINCT ON (asset_id, key)`
rather than a `DISTINCT ON (asset_id)`.

Start with the last entry. Most rounds are filled completely, it is one indexed query, and the
expansion is a later change to the same query with no schema consequence.

Four smaller decisions, all of which change the answer:

- **Which sheets count.** Completed only (`SUBMITTED`/`APPROVED`) — a draft on somebody else's
  tablet is not a recorded reading.
- **Not the current sheet.** It is the one being filled.
- **Voided submissions are already out**, for free: they live in `log_sheet_void_submissions`,
  not in `log_sheet_entries`.
- **"Last" by the sheet's completion time, with the id as tie-break.** One round closes many
  assets in the same millisecond; the house rule everywhere else in this codebase.

## The two surfaces are not the same problem

**The web fill page is easy.** One batch query for the sheet's assets at render, a
`Map<assetId, ...>` on the model, rendered beside each input — the batch-map shape
[performance.md](performance.md) §3 used for the units page. One query for the sheet, never one
per asset.

**The PWA is the whole cost.** It must work offline, so the value has to be in the bundle — and
the bundle is rebuilt on **every sync tick** (§3 above), which is already this system's known
scaling ceiling. A thirteenth query per sheet, multiplied by tablets and divided by the sync
interval, is exactly the arithmetic that section warns about.

What makes it affordable: **a previous reading is immutable relative to the round being filled.**
It cannot change while the operator works. So it belongs to the sheet when the sheet is first
pulled — sent once, stored in Dexie beside the sheet, and *not* recomputed per tick. Build §3's
option 1 (conditional GET) first and this rides along nearly free; build this first and it makes
§3's problem worse.

## The risk that is not technical

**Showing the last value makes people copy it.** An operator at a pump who sees «۲۴» is being
invited to write 24. This system has a data-quality report whose numbers this feature would move,
so the decision belongs to whoever owns that number, not to whoever writes the query.

Three shapes, cheapest first: show it **collapsed** behind a control, so reading it is a
deliberate act; show it, and flag for the supervisor when a new value is *exactly* equal to the
previous one; show a trend rather than the figure. The first is where to start.

Whatever is shown, **show its date with it**. A number with no date is read as "yesterday" when it
may be three months old.

## Related trap

If the class's field list changed, the previous value's key may not exist on the current sheet —
§5 above. Match on the key and show nothing when it is absent. Guessing is worse than silence.

## What would make it urgent

- Operators asking for it repeatedly, which is how it arrived.
- A parameter whose *change* matters more than its level: a bearing temperature creeping up over
  four rounds is invisible one reading at a time.

---

# 10. Voiding one asset's row on a log sheet

*Requested: "there should be a way to void one row of data — one asset — on a log sheet."*

## What happens today

There is no way to say **"this reading is not valid"**. There is only a way to say "this reading
is replaced by that one":

| Fact | Where |
|---|---|
| A supervisor reopens a sheet and corrects a reading; the replaced value is kept in `log_sheet_entry_revisions` and shown under «مقادیر پیشین» | [log-sheets.md](log-sheets.md) §3 |
| `recordSupersededValue` writes a revision **only when a value actually changed** | `LogSheetEntryRevisionService` |
| `log_sheet_void_submissions` voids a **whole submission the server refused** — a payload that never applied. It is not a per-row mechanism | [log-sheets.md](log-sheets.md) §6 |
| An entry carries no state of its own beyond its values: no `deleted`, no `voided_at` | [schema.md](schema.md) |
| `AttachmentService.delete` refuses on `APPROVED`, and `unapprove` is the documented way out | [log-sheets.md](log-sheets.md) §2 |

So the only way to neutralise a wrong reading is to **invent a replacement value**, which records
something nobody measured, or to leave it standing.

## The question that decides the design

**Is the reading invalid, or should the asset not have been on the round at all?**

- *The reading is wrong* — the asset stays on the sheet, its value is marked void, and ideally it
  becomes fillable again. The round is still incomplete until somebody re-reads it.
- *The asset should not be here* — decommissioned, inaccessible, scoped in by mistake. The row
  should stop counting as a **missed** asset, because nobody was ever going to read it.

These want opposite answers from every report: the first is "still outstanding", the second is
"not applicable". Building one and then discovering the request meant the other is the expensive
mistake here.

## Where the state would live

Three columns on `log_sheet_entries` — `voided_at`, `voided_by_user_id`, `void_reason` — rather
than a side table. Every read of an entry has to ask "is this void", and a join for a flag that
qualifies every row is the wrong trade. One migration, no new table.

**`form_data` is not cleared.** Void means marked, not erased — the same rule the rest of this
system follows, and the reason `log_sheet_void_submissions` keeps a whole refused payload. The
value stays, struck through, with who voided it and why.

## The part that will bite: `max_severity` is load-bearing

The obvious implementation is to null `max_severity` so the row stops counting. **Do not.** That
column is the *has-a-reading* test, and far more reads it than the reports:

| Reader | What it does with it |
|---|---|
| `countProgressBySheetId` | the numerator of every round's progress, on the list and the sheet page |
| `countBreachesBySeverity` | `IN ('WARNING','DANGER')` — the dashboard's breach cards |
| the data-quality and silent-asset reports | "was this asset actually read" |
| `IntegrationLogSheetDetail` | `maxSeverity` on the third-party API's payload |
| `idx_log_sheet_entries_asset_read`, `idx_log_sheet_entries_filled`, `idx_log_sheet_entries_breaches` | **three partial indexes** whose predicate is `max_severity IS NOT NULL` |
| `EntrySeverityBackfillRunner` | selects rows to evaluate by `max_severity IS NULL` |

Nulling it to mean "void" would silently redefine "has a reading" for all of them, hand three
indexes a different population, and let the backfill runner pick the row up on the next boot and
undo it.

**So: filter on the void flag, never overload the severity.** Every reader in that table has to
learn the new predicate — and enumerating them is most of the work in this feature, not the
endpoint.

## The rest of the blast radius

- **The mobile contract.** `LogSheetEntryDto` carries no void flag, so a tablet holding the sheet
  keeps its own copy and resubmits it, resurrecting the row. The flag has to reach the device and
  the merge has to honour it — which touches `wouldBlankUnseenAnswer` and the PWA's
  `applyLogSheetBundle`.
- **Attachments.** Files a voided reading references are evidence and must survive. The
  `protectedAttachmentIds` machinery already shields revision- and void-submission-referenced
  rows; voided entries need adding to it, or the next sweep reclaims the photograph of the fault
  that caused the void.
- **Lifecycle.** Follow the attachment rule: allowed while the sheet is open or delivered, refused
  on `APPROVED`, with `unapprove` as the way out. Otherwise a sign-off can be emptied of its
  content after the fact — the exact defect fixed in the attachment path.
- **Permission.** Its own endpoint permission, and a capability if a supervisor may void on any
  unit. `POST:/asset-status-requests/{id}/decide` is the nearest model.
- **Re-filling.** If void means "still outstanding" there must be a route back to filling it, and
  the void has to survive that — or the operator's next save silently un-voids it.

## What would make it urgent

- An audit finding a completed round whose numbers include a reading everybody knew was wrong.
- Assets that are routinely unreachable — scaffolding, a locked room — inflating the missed-asset
  report every round. That is the *second* reading of the question above, and it is the cheaper
  one to answer.

---

# 11. Telling another system that an approved round breached a band

*Requested: "when a value is recorded, the sheet is approved, and the value is in the warning or
danger band, call a webhook or invoke a service."*

## What exists today

| Fact | Where |
|---|---|
| Severity is already computed and stored per entry — `max_severity` is `OK`/`WARNING`/`DANGER`, written on every save | `EntrySeverityEvaluator` |
| Approval is a discrete, recorded act with its own permission and an **undo** | `LogSheetAssignmentService.approve` / `.unapprove`, `POST:/log-sheets/{id}/approve` |
| `AssetStatusRequestService.raiseFromCompletedSheet` already derives work from a finished sheet — the nearest precedent for "when a sheet reaches a state, act on it" | `LogSheetService.completeFromWeb` |
| A third party can already **pull**: `GET /integration/v1/log-sheets?from&to&statuses=APPROVED`, backed by `idx_log_sheets_status_finalized_at`, and the detail carries `maxSeverity` per entry | [log-sheets.md](log-sheets.md) §7 |
| `auditExecutor` exists for fire-and-forget writes, with `CallerRunsPolicy` deliberately chosen so a full queue slows the producer instead of dropping work | `AsyncConfig` |
| **The server makes no outbound HTTP call anywhere.** No `RestTemplate`, no `WebClient`, no `HttpClient` in `src/main` | — |

That last row is the one to think about first. This system is built to run on an isolated plant
LAN — nginx forwards `/api/`, `/integration/**` is not even proxied ([§2](#2-request-rate-limiting))
— and a webhook is the first thing that would make the server *depend on reaching something else*.

## The framing is slightly wrong, and fixing it simplifies everything

The request says "when a value is recorded **and** the sheet is approved". Those happen at
different times, usually days apart: the operator records in the field, a supervisor approves
later. Hooking the value write means storing "this is pending approval" and re-checking it later.

**The trigger is approval.** At approval, the sheet's entries are already stored and already carry
`max_severity`; scan them, and emit for the ones in band. One trigger, one place, no pending state.

Two consequences that follow immediately:

- **`unapprove` then `approve` again would fire twice.** The event needs a stable identity —
  (sheet, asset, field, approval instant) or a generated id — and the receiver must be told that
  redelivery is possible.
- **A correction after approval** (reopen → edit → re-approve) changes the values. Decide whether
  that is a new event, an amendment, or nothing.

## Consider not building it

The consumer can already poll `GET /integration/v1/log-sheets?statuses=APPROVED&from=…&to=…` and
read `maxSeverity` from the detail. That answers the stated need with **no new infrastructure, no
outbound network from the plant, and no delivery problem to solve** — the consumer's cursor is its
own business, and a consumer that is down simply catches up.

What polling costs is latency, bounded by the poll interval. If "within a few minutes" is
acceptable — and for a round approved hours after the reading was taken, it usually is — this is
the whole feature, and it is already built.

**Push is worth building when the latency genuinely matters** (an alarm system, a control room
display) or when the consumer cannot poll. Establish which of those it is before writing any of
what follows.

## If push is needed: never call out from the approval transaction

The one implementation to rule out first. Calling the webhook inline means:

- a slow endpoint makes a supervisor's approval slow;
- a failed call has to either fail the approval — a plant's sign-off must not depend on somebody
  else's server being up — or be swallowed, which is a notification silently lost;
- and a call made before the transaction commits announces something that may then roll back.

**The shape that works is an outbox.** In the same transaction as the approval, insert rows into a
`webhook_outbox` table: the event, its stable id, its payload, `attempts`, `next_attempt_at`,
`delivered_at`, `last_error`. Nothing leaves the process. A scheduler drains it — the codebase
already runs several ([jobs.md](jobs.md)) — and the approval is committed and complete regardless
of whether anything is listening.

Three properties the drain must have, and the third is one this codebase has already paid for:

1. **At-least-once, and say so.** A crash between "sent" and "marked delivered" means a resend.
   The receiver dedupes on the event id; this must be in whatever document the integrator gets.
2. **Backoff, and a give-up.** `attempts` and `next_attempt_at`, with a ceiling after which the
   row is left as failed and visible, not retried forever.
3. **One bad row must not wedge the queue.** Skip it and carry on. The tablet's attachment delete
   queue learned exactly this: a row the server had never heard of stopped the whole pass, and
   every deletion behind it, on every future pass.

## Security, which is most of the remaining work

- **The URL is configuration, not data.** An admin-editable outbound URL is a way to point the
  server at an internal address and read the response, and a way to send plant readings somewhere
  new without touching the code. Put it in `application.properties` beside the other `app.*`
  settings, not in the Settings page.
- **Sign the body.** HMAC-SHA256 with a shared secret in a header, so the receiver can tell a real
  event from anything else that can reach its endpoint. The receiving side is usually the weakest
  part of a webhook.
- **The secret must not reach the logs.** `LogSanitizer` masks field names containing
  password/token/secret/credential — name it so it is covered, and check.
- **Timeouts are mandatory**, connect and read. Without them a hung receiver holds a scheduler
  thread indefinitely.
- **Decide what is in the payload.** Sheet, asset code, parameter, value, unit, band, threshold and
  time are what make it actionable. The **operator's name** is personal data crossing a boundary —
  a decision, not a default.

## What would make it urgent

- A band whose breach needs acting on within minutes rather than by the next shift.
- A consumer that genuinely cannot poll — a system that only accepts pushes.
- Somebody discovering that a `DANGER` reading sat in an approved round for a week because the
  only thing that reads them is a report nobody opened.

---

# 12. S3-compatible object storage for attachments

*Raised in conversation, not by a defect: attachments currently work correctly. The question was
whether an S3-compatible store (MinIO, on the plant's own network — not AWS) is a better place
for them than the local filesystem. It is a real trade, not a clear win either way, and the
answer depends on a scale and a durability requirement neither of which is established yet.*

## What exists today

| Fact | Where |
|---|---|
| Attachment bytes live under `app.attachments.storage-dir`, date-sharded (`2026/08/06/<uuid>.jpg`) so no directory ever holds more than roughly a day's uploads | `AttachmentStorageService` |
| Every access goes through **one class** with a six-method surface (`store`, `read`, `exists`, `delete`, `forEachStoredFile`, `getRoot`) | `AttachmentStorageService` |
| Only **two callers** touch it: `AttachmentService` (upload/download/delete) and `AttachmentSweepService` (the orphan sweep) | — |
| `getRoot()` — the one method that leaks a filesystem `Path` — is called **only from tests** | `AttachmentSweepIntegrationTest` |
| The row is the source of truth; `sha256`, `size_bytes` and `storage_key` (`UNIQUE`) are already columns, computed and stored on every upload | V1 migration, `AttachmentService.upload` |
| Nothing verifies the checksum again after the fact — it is written once and never re-read | — |
| Writes are already atomic (temp file + `Files.move`) and reads never trust a client-declared MIME type — both properties an object store gives natively | `AttachmentStorageService.store`, `.detectMimeType` |
| **The server makes no outbound HTTP call anywhere** — no `RestTemplate`, `WebClient`, or `HttpClient` in `src/main`, confirmed already in [§11](#11-telling-another-system-that-an-approved-round-breached-a-band) | — |
| `UiAssetsStayLocalTest` fails the build on any `https://` reference in a template, stylesheet or script | — |
| Photos are never deleted by age; the orphan sweep removes *files with no row*, not old files — see [README § Disk for attachments](../README.md#disk-for-attachments-the-one-number-to-plan) | — |
| The application is single-node today: `SessionRegistryImpl` and `LoginAttemptService` are in-memory, and all five `@Scheduled` jobs run with no distributed lock | `WebSecurityConfig`, `LoginAttemptService`, `AttachmentSweepService` + 4 others |
| `attachments.log_sheet_id` is `ON DELETE CASCADE`, but **nothing in the codebase deletes a log sheet row** — the cascade is unarmed, not inert by design | V1 migration |
| Testcontainers is already the pattern for an infrastructure dependency in tests — `AbstractPostgresIntegrationTest` runs a real `postgres:16-alpine` per test class | — |
| No S3/AWS/MinIO client is on the classpath today | `pom.xml` |

## Two different proposals hide under one name

**S3 as the AWS service** is not on the table and is not what this section is about. The plant
network is isolated by design, and `UiAssetsStayLocalTest` exists specifically to keep it that
way; sending attachment bytes to a public cloud would be a different project.

**S3-compatible storage on the plant's own network** — MinIO or Ceph, reachable only from inside
the LAN nginx already serves — is the real proposal, and the rest of this section is about that.

## The case for it

**Object Lock (WORM) is the strongest reason, and it is qualitatively different from the rest.**
Everything else below is an operational trade this project can absorb either way; Object Lock is
a property a filesystem cannot give at all. It turns "attachments are never deleted" from a rule
people and code have to keep honouring into a constraint the storage layer enforces regardless —
closing the one gap the orphan sweep has today: nothing stops it from removing a file that is
genuinely still needed if the row that referenced it went missing for a reason other than
deletion (a restore to an earlier backup is the concrete case — rows go back in time, files do
not, and the sweep cannot tell that apart from a row that was legitimately removed). If retention
is a compliance question — how long an inspection photo must survive, provably unaltered — this
is the argument that matters and the others are secondary to it.

**Erasure coding and bit-rot healing** answer a real gap: `sha256` is written once and never
re-checked, so silent corruption over a period measured in years is currently undetectable until
somebody opens the file. This is buildable without object storage (a periodic re-hash job over
existing rows), but MinIO does it as a property of the store rather than a job someone has to
remember to write and keep running.

**A path to more than one node**, if that is ever wanted. Today it is not: the reasons a second
application instance cannot run are the in-memory session registry and login-attempt tracking and
the five unguarded scheduled jobs, none of which storage touches. Moving attachments to a shared
store removes exactly one of several blockers, not the constraint itself.

## The case against it, at the current scale

**The other blockers do not move.** Buying multi-node readiness by migrating storage alone is a
partial purchase — the session store and job locking would still need solving before a second
node could safely exist.

**A new service that can be down while the application is up.** Today the file store and the
application share a lifetime. MinIO is a second process an unattended plant server now depends
on, with no dedicated operations team to run it.

**The actual problem is retention, and it is orthogonal to where the bytes live.** Nothing here —
filesystem or object store — currently deletes an attachment by age; that is a policy nobody has
written yet, on either substrate. See [README § Disk for attachments](../README.md#disk-for-attachments-the-one-number-to-plan)
for the current growth curve. If the requirement is genuinely "never delete, may be recalled
later," retention stops being a knob to add and starts arguing for exactly the Object Lock case
above — the two are connected, not independent choices.

**Backup does not get simpler by default.** The efficient path today — `rsync --link-dest`
against a filesystem whose files are immutable once written and whose shards are date-ordered —
has no automatic equivalent for MinIO; its own backup is a copy of comparable size unless
something equivalent (`mc mirror`, incremental snapshotting) is set up deliberately.

## What migrating would actually touch

**Small, precisely because of the encapsulation already in place.** Two callers, a six-method
interface, and the one method that exposes a filesystem `Path` is used only by tests. Swapping the
implementation behind `AttachmentStorageService` is on the order of the six methods, not a
project-wide change — and several things get *simpler* on an object store: no temp-file-then-move
(`PUT` is atomic on its own), no `resolveWithinRoot` path-traversal guard (a key prefix check
replaces it), no `pruneEmptyDirectories` (there are no directories).

**The risk is not in the code change.** It is in:

- **Migrating existing bytes** while the system stays live — `size_bytes` and `UNIQUE(storage_key)`
  make a completeness check scriptable (every row's key exists in the target with the recorded
  length), but it still has to run against however many files have accumulated by the time this is
  attempted, and be re-run after cutover to catch anything written during the copy.
- **The cutover window** — an upload arriving mid-migration must not be silently lost. The
  standard shape is dual-write (write to both stores for a period, read from the old one, switch
  reads, then retire the old one after a grace period), not a single flag flip.
- **New operational surface** — credentials, TLS, MinIO's own backup, patching, monitoring — for a
  site with no dedicated infrastructure team, this is a standing cost, not a one-time migration
  task.
- **Test infrastructure** — `AttachmentSweepIntegrationTest` and `AttachmentApiIntegrationTest`
  exercise real files via `getRoot()` and would move to a MinIO Testcontainer; the pattern already
  exists for PostgreSQL in `AbstractPostgresIntegrationTest`, so this is following a precedent
  rather than establishing one.

## What would justify doing this

Not a size threshold by itself — the filesystem layout does not degrade with more files, and
neither the shard scheme nor the sweep query slows down mechanically as the count grows within any
plausible range for this deployment. The triggers are qualitative:

1. **A retention requirement that has to be *provable*, not merely practiced** — an auditor or a
   regulator asking not "do you keep these" but "can the storage itself guarantee nothing was
   altered or removed." This is the Object Lock case, and on its own it can justify the migration.
2. **A second application node is actually being built**, after the session-store and job-locking
   blockers are addressed — at that point shared storage stops being optional.
3. **A volume or durability requirement the current single-disk layout cannot satisfy** — RAID or
   ZFS on the existing filesystem answers most of this more cheaply; object storage becomes the
   better answer once the requirement crosses into erasure coding across multiple machines.

Absent one of these, the lower-risk work is the retention policy itself (see [README § Disk for
attachments](../README.md#disk-for-attachments-the-one-number-to-plan)) and a periodic
`sha256` re-verification job — both buildable on the current filesystem, both needed regardless of
which storage this project ends up on.

---

# 13. A form builder beside the log sheet

*Raised after go-live: other units — HSE first — want the offline capture this system does, for
records that are not rounds. Roughly ten forms, some with sub-forms, fields with rules, and their
own reference data. **This section is a design conversation written down, not a specification.**
The reasoning is recorded so it does not have to be had again; nothing here is built, and several
questions are still open at the end.*

## What exists today, and how much of it is already this feature

| Fact | Where | Reusable as-is? |
|---|---|---|
| Field definitions — key, label, data type, unit, required, `validation` jsonb, display order | `field_definitions` | ✅ except `class_id NOT NULL` binds every definition to an asset class |
| Ten data types, one whitelist, mirrored in the PWA with a build-failing parity test | `FieldDataTypes` ↔ `FieldDataTypesTest` | ✅ |
| The validation engine — required, select options, number, checkbox, attachment shape | `FormDataValidationSupport` | ✅ |
| The dynamic renderer, driven by `fields: FieldDefinition[]` | `DynamicClassForm.tsx` | ✅ except its `attachmentContext` is log-sheet shaped |
| Values as `jsonb`, filtered by `answeredOnly` / `retainKnownKeys` | `form_data` | ✅ proven at 200,000 assets |
| Freezing the schema at capture time | `field_definitions_snapshot` | ✅ the pattern to copy |
| **The hierarchy is already cached offline, with parent links** — `operationalUnits: 'id, code, parentId'`, same for locations and sub-functions | PWA `db.ts` (Dexie **v3**) | ✅ dependent-option rules over units need **no new master data** |
| Offline queue discipline — `clientActionId`, own-work-only on shared tablets, `syncStatus` as the queue, deletions first, one bad row must not wedge the pass | `logSheetSync` / `attachmentSync` | the *rules*, not the code |
| `attachments` — `log_sheet_id NOT NULL` FK (ON DELETE CASCADE), `asset_id NOT NULL` FK, `field_key NOT NULL` | V1 | ❌ log-sheet shaped |
| `log_sheet_entries.asset_id` is **nullable in the schema**, but the application treats entries as asset-bound everywhere — `requireEntry(sheet, assetId)`, the scope CTEs, the asset-parameter and silent-asset reports | — | ❌ permissive schema, strict application |
| `nfc_fault_reports` looks like a standalone device-created record, but is **not** — `log_sheet_id NOT NULL`, `asset_id NOT NULL` | V1 | proves the *mechanism*, not the shape |

**No record in this system is currently about anything other than an asset on a round.** A form
submission would be the first. That is the main reason it must be kept separate, and also why it
costs more than it looks: every cross-cutting concern here — scope, attachments, sync, reports —
assumes the log-sheet world.

## The sentence that keeps this small

> **It is not the ERP.** A record is captured, synced, and handed to an ERP where the process
> continues.

Write it down with an explicit **non-goal list**, because each of these will be asked for and the
only defence is a sentence somebody already agreed to:

- no workflow, routing, escalation, or multi-step approval — **exactly one optional approval step**
- no revisions and no «مقادیر پیشین»: a submitted form is **immutable**; a supervisor who
  spots an error **rejects** it with a reason and the operator files again
- no analytics over form data — that is the ERP's job
- the log sheet does **not** become "just another form"

## Where it should live: this project, with a hard internal boundary

Same repository, same deployment, same APK. In order of weight:

1. **One APK.** The fleet is updated by **uninstall-and-reinstall** with a server-side delivery
   check (the PWA's `docs/apk.md`). A second app means two CA installs, two sync engines on one
   device, and **two fleet sweeps** — doubling the riskiest operation there is, the one that
   destroys unsynced work when it goes wrong.
2. **One sync engine.** It is the hardest thing here; duplicating it duplicates every subtlety
   this project has already paid for.
3. **One identity and scope model.** HSE users are the same people in the same units.
4. **The data references the same hierarchy** — a record names a unit, a location, sometimes an
   asset. A separate database turns those into foreign ids with no constraint, and splits the
   backup and restore story `deployment.md` is built around.
5. Ten forms does not justify a second system.

**What that costs, and how it is contained.** The real tension is release cadence: an HSE change
should not force a redeploy of the system running the plant's rounds. Three things hold the line —

- **A boundary that is enforced, not agreed.** The package layout here is by technical concern
  (`service/`, `web/`, `repository/`), so a feature boundary does not appear on its own. It needs a
  rule — *nothing on the log-sheet path may depend on forms; no FK from forms to `log_sheets`* —
  and a **build-failing test that enforces the direction**. This codebase already carries three
  tests of exactly that kind (`FieldDataTypesTest`, `LogbackProfileConfigTest`,
  `FormPatternAttributeTest`).
- **A feature flag**, so forms ship dark and are enabled per installation.
- **Its own `docs/forms.md`**, under the same same-commit documentation rule.

**Staying inside is reversible; splitting now is not.** If HSE later becomes its own product with
its own budget, a well-bounded module is *extracted*. Split today and the duplication is paid
immediately, for a maybe.

If HSE turns out to have entirely separate devices, separate users, and no reference to the plant
hierarchy, four of the five reasons above evaporate and a separate project becomes defensible.
That is the question to re-ask before starting, not after.

## The two ways a submission is born

| Case | Born | Initial state |
|---|---|---|
| A violation report the operator raises offline | on the **device** | straight to submitted, carrying `client_action_id` |
| A form put out "awaiting data", picked up and filled | on the **server** | `AWAITING_DATA` → taken → submitted |

One table, one status column, two births — the same shape a log sheet already has (raised empty,
filled later), minus every piece of asset machinery.

## Sub-forms are child submissions, not nested JSON

A violation carries *several named collections* — offending persons, offending vehicles — each
with its own field set. Nested `jsonb` encodes that as arrays of differently-shaped objects, and
then:

- `FormDataValidationSupport` has to recurse and learn which sub-schema applies to which array;
- `answeredOnly` / `retainKnownKeys` filter only top-level keys, so unanswered sub-fields leak in —
  **the exact contamination V3 had to repair once already**;
- and **attachments break**. They key on `field_key`; a photo of the second offender needs a row
  index too, and an index is not a stable identity — delete row 1 and the photo follows the wrong
  person.

So: **a sub-form is its own `form_definitions` row**, and each filled row is its own
`form_submissions` row carrying `parent_submission_id`, `parent_section_key` and `row_order`.

Everything then reuses what exists: validation runs per child against its own fields, unchanged;
attachments key on the child submission id, which **eliminates** the row-identity problem rather
than solving it; children push after the parent, gated on its server id, which is exactly what the
attachment queue already does; the renderer draws N instances of the existing one. Adding a third
collection is a new form definition and **no code**.

## Reference data is its own small module

Work shifts, vehicle types, violation types. Not a table each:

```
lookup_lists (id, code UNIQUE, name, name_fa, active)
lookup_items (id, list_id, code, label, label_fa, parent_item_id, order, active)
```

`parent_item_id` **from day one**. The "children of" rule wanted for organisational units is
wanted for user-defined lists too (vehicle type → model), and adding a parent to a list that
already carries data — and rules pointing at it — costs far more later than a nullable column now.

It also collapses the option-source vocabulary to two kinds: `lookup:<code>`, and `system:<name>`
for the few things that already have tables.

**Store the label beside the code** in `form_data`:
`{"shift": {"code": "A", "label": "شیفت صبح"}}`. Two reasons — §5 above (a record that outlives its
definition must still read correctly), and the ERP does not know these ids. Use `active` rather
than delete, so a list can never lose a row something already referenced.

## A closed rule vocabulary, never a rule language

What is wanted: options drawn from reference data; options that depend on another field's value;
a field required only if another is ticked; a field visible only if another is ticked; a defined
display order.

**Do not build a general expression evaluator.** It would have to exist **twice** — Java and
TypeScript — and behave identically, or the device accepts what the server rejects and an operator
fills a form offline that can never be submitted. A user-authored rule language evaluated on the
server is also a new security surface for no gain.

Instead, a finite list, stored in the `validation` jsonb that already exists:

```jsonc
{ "optionsFrom": "lookup:violationTypes" }
{ "optionsFrom": "system:operationalUnits", "dependsOn": "unit1", "relation": "children" }
{ "requiredIf": { "field": "hasInjury",  "equals": true } }
{ "visibleIf":  { "field": "hasVehicle", "equals": true } }
```

Display order is `field_definitions.order`, which already exists.

**Three rules that have to be decided now, not later:**

1. **References stay in scope.** A rule inside a sub-form row may name fields of *that row* only,
   never the parent's. Cross-scope references are where this design explodes.
2. **A hidden field is neither required nor kept.** When `visibleIf` turns false the value is
   **cleared** — otherwise the record answers a question nobody was asked. Same family as the
   cleared-multiselect defect (§8b).
3. **The server re-evaluates.** The device hides (a UX concern), the server validates (the truth).
   A value arriving for a field that should have been hidden is **stripped**, which is what
   `retainKnownKeys` already does for a different reason.

Both sides must also agree on what `children` means — direct children, or the whole downward
branch? (`getAccessibleUnitIds` expands downward.) Define it once, with a **shared test-vector
fixture both sides run**, or the two implementations will drift the first time either is fixed.

## Master data reaches the tablet by grant, as one versioned bundle

A user with HSE form access should get HSE's reference data on their tablet and nothing else:
granted forms → the lookup lists those forms declare → what syncs.

**The trap:** shipping the form definition and its lists as independent stores lets a tablet hold a
form whose option list it does not have — it renders, and every dropdown is empty. So ship **one
versioned bundle**: the form, its sub-forms, and every list they reference, together. The tablet
either has the whole bundle or none of it — the same thinking as `field_definitions_snapshot`.

Two behaviours to write down while designing, not discover in the field: a grant added while a
tablet is offline is not usable until the next sync (acceptable), and a grant removed leaves stale
lists that the next sync must clear.

## Attachments: one nullable column, not a twin table

Required — the violation form carries photographs, video, and voice notes.

`attachments` is log-sheet shaped, and the three ways out are not equal:

| Option | Cost |
|---|---|
| Generalise to `(owner_type, owner_id)` | Loses both FKs and the `ON DELETE CASCADE`, which does real work today |
| **Nullable `form_submission_id` + a `CHECK` that exactly one owner is set** | ✅ keeps both FKs and the cascade; `AttachmentService` grows a branch, not a twin |
| A separate `form_attachments` table | A second copy of the sweep, `enforceCount`, `protectedAttachmentIds`, the reference reconciliation, and `AttachmentFieldInput` — precisely where this project's worst bugs have lived |

One known piece of work: `adoptIntoFieldReference` writes into `log_sheet_entries.form_data` and
would need an owner abstraction to write into `form_submissions.form_data` instead. Bounded — and
the map of everything it must not break is AGENTS.md #123.

On the device, `AttachmentFieldInput` keys on `(logSheetLocalId, assetId, fieldKey)` and would need
a general owner key — which changes the Dexie `attachments` store, so **`version(4)`**, and the
standing rule holds: never edit an applied `version(n)`.

## One optional approval, and the handoff to the ERP

A supervisor may need to approve a synced form before it becomes a candidate for the ERP.

**Two columns, not one enum.** `approval_status` is a *human decision*
(PENDING/APPROVED/REJECTED); `handed_off_at` is a *transport fact*. Merged into one status they
multiply into `APPROVED_NOT_SENT`, `APPROVED_SENT`, `APPROVED_SEND_FAILED`… Kept apart, the ERP's
query is one line:

```sql
WHERE (NOT requires_approval OR approval_status = 'APPROVED') AND handed_off_at IS NULL
```

`requires_approval` lives on the **form definition** — a violation needs review, a routine
inspection log probably does not.

Two rules follow from how this codebase already reasons: **a rejection carries a mandatory
reason** (it is the only thing the operator gets, since the submission is immutable), and
**`unapprove` is allowed only while `handed_off_at IS NULL`** — withdrawing approval after the ERP
holds the record is meaningless, the same reasoning that freezes attachments on an APPROVED sheet.

The review queue needs no invention: `asset_status_change_requests` is already a unit-scoped
PENDING/APPROVED/REJECTED queue with filters and a `.../decide` endpoint, and its scope query has
already been rewritten to survive 200,000 assets. Copy its shape and its permission model, and
plan for **bulk approval** from the start — a supervisor with sixty rows will otherwise click
sixty times.

**Getting it to the ERP is §11's question, with the same answer.** Approval is the trigger, and
*pull* is the cheap correct option: the ERP polls for approved-and-not-handed-off submissions
through the integration API that already exists (`/integration/v1/...` already filters by status
and finalisation time) — no outbound network from the plant, no delivery problem, no outbox.
Push only if latency genuinely demands it, and then never from inside the approval transaction.
The handoff must be idempotent, and the export document should be a **flat, versioned contract**
assembled from parent and children — do not make the internal tree the external contract.

## Access control, in three layers

| Layer | What it answers | How |
|---|---|---|
| **Static** | may you use forms at all? | ordinary permissions and a capability, seeded in a migration |
| **Dynamic** | *which* forms may you fill / see / approve? | `form_grants(form_id, role_id, action)` — FILL, VIEW_SUBMISSIONS, APPROVE |
| **Scope** | *whose* submissions do you see? | the existing `visibleUnitIds()` |

**Do not mint a `permissions` row per form.** Those are seeded by migration and named by constants
in `PermissionCodes`; a form deleted at runtime would leave an orphan permission behind.
Role-keyed grants are the same shape as `unit_supervisors`, which is already a runtime grant and
already understood.

Three rules that are cheap now and expensive later: **fail closed** — a new form is fillable by
nobody until granted; **the server resolves, the client renders** — `GET /api/forms` returns the
resolved list, never the rules, and the server re-checks on submit; and **the device must not
choose the scope** — `operational_unit_id` is derived from the submitter or validated against
their scope, or an operator can file against a unit whose supervisor has never heard of them.

**Revoked while offline** is the case to design for, and the answer already exists in this system:
keep the work, mark it void with a reason, show it for review. That is what `voidSubmission` does
for a log sheet (§6).

## Is PostgreSQL the right store? Yes

- `jsonb` is the right tool for a payload whose schema is per-form, and the pattern is proven here
  at 200,000 assets — V3 even rewrote every `form_data` in place.
- The trap to avoid is **EAV** (a row per field value), which turns every read into a pivot and
  every report into a join ladder.
- One datastore means one backup, restore, security and retention story. `deployment.md` is built
  around `pg_dump`; a document store would mean a second one of each, for a site with no dedicated
  infrastructure team.
- Where it needs care is querying *across* submissions by a field's value. The levers are a GIN
  index (`jsonb_path_ops`) or generated columns for the few fields actually reported on.
  **Measure first**, per `performance.md`.

## One hard limit to accept up front

`optionsFrom` may name only **bounded** sources. Units (~4) and locations (~180) are fine.
**Assets are 200,000** and can never be a dropdown on a device. A form that needs an asset uses an
NFC scan, or a typed code resolved against the local index (`assetEntries` is indexed on
`nfcTagId`). Close this in the whitelist from the start, or the first form that asks for an asset
takes the tablet down — and it will be asked for in the first week.

## Risks, worst first

1. **Two schema languages drifting.** If `form_fields` becomes a second `field_definitions`, the
   type whitelist, the validator, the renderer, and the PWA's union all fork, and they diverge the
   first time either is fixed. Mitigation: one table with a nullable parent and a `CHECK`, or an
   enforced equivalence test.
2. **Attachments** — the single most expensive integration point, and the area with this project's
   worst historical bugs (AGENTS.md #123, §8a).
3. **A second offline queue** with the same subtleties as the first. Getting it wrong loses field
   data, which is the one failure this system exists to prevent.
4. **Scope creep into a workflow engine.** One approval is fine; the ladder from there is short and
   every rung looks reasonable on its own.
5. **Access-control drift** — a second authorization model living beside the existing one.
6. **Offline staleness** — a tablet a week behind holds an old definition. The snapshot protects
   what is already stored; the *renderer* needs a version and a staleness policy.
7. **Dexie migration** — `version(4)`, and never edit an applied version.

## Suggested order

| Phase | What | Why here |
|---|---|---|
| **0** | Settle the open decisions below | Storage shape is the one thing that cannot be changed cheaply later |
| **1** | Reference data (`lookup_lists` / `lookup_items`) | Independent, small, and **useful on its own** even if nothing else is built |
| **2** | A flat form: definition, and capture **on the web only** | Proves the model without touching sync |
| **3** | The rule vocabulary | On a model that already works |
| **4** | Offline capture in the PWA, and its sync | Where the value actually is |
| **5** | Attachments | The most expensive join |
| **6** | Approval and the ERP handoff | Needs real captured data before it is worth anything |
| **7** | Sub-forms | Biggest, and better decided after seeing real forms in use |

## Still open

- **Pull or push to the ERP.** Try pull first; it may be the whole answer.
- **Does the operator ever learn a submission was rejected?** Web-only is cheap; returning status
  to the tablet opens a new *inbound* sync path, a retention question, and a new store. Phase 1:
  web only — but say so out loud, because the operator is the one who has to file again.
- **Case 2 assignment** — to one user, or to a unit/role pool? The log sheet supports both
  (`AssignmentType`) and the choice changes every screen.
- **Retention after handoff.** The record presumably stays, read-only — but decide now, or in three
  years nobody will know whether it may be deleted.
- **A ceiling on offline creation.** A device can raise 500 records offline; log sheets have
  `LogSheetSizeLimits` and this needs an equivalent before, not after.
- **Is a builder even right?** Ten forms is past the "just write them" threshold, and dependent
  rules are exactly where builders turn into projects. It is the right call *if* the rule
  vocabulary stays closed — and if users are genuinely meant to author forms themselves rather
  than ask for one.

## What would make it urgent

- A second unit committing to it with a date.
- HSE capturing on paper today — which is precisely the failure this system was built to end.
