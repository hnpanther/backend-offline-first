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

- **A feature that does not exist** (§1, §2, §3, §4, §9, §10, §11, §12, §13, §15, §16, §17, §19) — nothing in the description is running.
- **A proposal that was weighed and declined** (§14) — kept because the argument is what stops it being re-proposed every year, and because the conditions that would change the answer are written into it.
- **Behaviour that does exist, with a decision about it deliberately deferred** (§5, §7, §8, §18). The
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

# 5. A reading whose field no longer exists — **mostly closed**

**Two of the three options below are built.** What is left is narrower than what this section
originally described, and is written down so the remainder is not re-argued from scratch.

## What used to happen

A class's parameter list changes: one is added, renamed or dropped. Readings already stored
under the old key stay in `log_sheet_entries.form_data`.

`/reports/asset-parameters` iterates that stored `form_data`, so the value always appeared in
the table — but the label, the unit and the thresholds came from a live `field_definitions`
row, and a **hard-deleted** parameter has none. The row fell back to the raw key with no unit
and no colouring, vanished from the parameter dropdown, and could not be charted. The log sheet
meanwhile still rendered it properly from its own frozen snapshot, so the sheet and the report
disagreed about the same reading.

## What is built

- **Option 2, the soft delete — built.** `field_definitions.deleted` is now written, by
  `POST /asset-classes/{classId}/fields/{fieldId}/state`, and the reports resolve through
  `LogSheetFieldDefinitionsService.resolveForReporting`, which carries a disabled definition
  through marked instead of dropping it. Label, unit, severity and chart all come back. §23.
- **Option 3, marking a retired parameter — built.** The history row carries a «غیرفعال» badge
  and the dropdown entry is suffixed, so a series that stops partway reads as "switched off"
  rather than as missing data.
- **The hard delete is now refused** once anything names the key, so the "no row left to read a
  label from" case can no longer be created by the panel. It survives only for a parameter
  created, never answered, and removed again.
- **Renaming is a data migration** (V6, [AGENTS.md](../AGENTS.md) #157), not an edit — so the
  renamed-field case this section called "genuinely misleading" is handled by carrying the
  readings with the key rather than by labelling them afterwards.

## What is left — option 1, and it is now small

**Label from the sheet's own snapshot.** Each reading row knows which log sheet it came from,
and that sheet froze the definition as it was. Resolving the label, unit and thresholds from
`field_definitions_snapshot` — falling back to the live definition, then to the raw key — is
still the only thing that reports a *historical* label rather than today's.

What it would buy, now that the two cheap options are in:

- A parameter whose **label** (not key) was re-typed — «دما» to «دمای ورودی» — shows the new
  label against an old reading. Today's report is not wrong about the value, only about what it
  was called at the time.
- A parameter whose **band** was re-tuned: the chart draws today's threshold lines across
  history, which is deliberate (§ reports.md §7: the chart asks "what is this machine's range")
  but is not the same question as "was this reading a breach when it was taken". The stored
  `max_severity` answers that one and the table already uses it.

The cost is a join from each reading to its sheet's snapshot, per page. Not free, and not
obviously worth it until somebody is actually re-labelling parameters.

## What would make it urgent

- Parameter **labels** start being edited routinely rather than set up once.
- Somebody exports the parameter report for an audit and a column header does not match the log
  sheet the auditor is holding.

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

One `POST /log-sheets/{id}/entries/{entryId}/draft` calls `findByLogSheetId` **twice** — the
ownership check and the loop inside `applyWebEntryValues` — and `findForLogSheet` once, which
loads every attachment on the sheet to render one card. (It was three: the re-render asked for
the whole sheet's definitions a third time. That read is gone — the re-render needs one entry's
schema and now asks `resolveForEntry(sheet, saved)` for it.)

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

# 11. Telling another system that a round breached a band

*Requested: "when a value is recorded, the sheet is approved, and the value is in the warning or
danger band, call a webhook or invoke a service."*

## What exists today

| Fact | Where |
|---|---|
| Severity is already computed and stored per entry — `max_severity` is `OK`/`WARNING`/`DANGER`, written on every save | `EntrySeverityEvaluator` |
| Approval is a discrete, recorded act with its own permission and an **undo** | `LogSheetAssignmentService.approve` / `.unapprove`, `POST:/log-sheets/{id}/approve` |
| `AssetStatusRequestService.raiseFromCompletedSheet` already derives work from a finished sheet — the nearest precedent for "when a sheet reaches a state, act on it" | `LogSheetService.completeFromWeb` |
| **Severity reaches the server mid-round, not only at submit.** `saveProgressBatch` and `submitBatch` share `mergeMobileEntryUpdates`, which calls `EntrySeverityEvaluator.apply` — so a breach is stored as soon as a tablet with a link pushes progress | `LogSheetService:435` |
| **The operator is already warned, locally and instantly.** The PWA evaluates severity client-side while the value is being entered, offline included | `evaluateFieldSeverity`, AGENTS.md #156 |
| A third party can already **pull**: `GET /integration/v1/log-sheets?from&to&statuses=APPROVED`, backed by `idx_log_sheets_status_finalized_at`, and the detail carries `maxSeverity` per entry | [log-sheets.md](log-sheets.md) §7 |
| `auditExecutor` exists for fire-and-forget writes, with `CallerRunsPolicy` deliberately chosen so a full queue slows the producer instead of dropping work | `AsyncConfig` |
| **The server makes no outbound HTTP call anywhere.** No `RestTemplate`, no `WebClient`, no `HttpClient` in `src/main`, and no HTTP-client dependency in `pom.xml`. Re-checked 2026-10-28 | — |

That last row is the one to think about first. This system is built to run on an isolated plant
LAN — nginx forwards `/api/`, `/integration/**` is not even proxied ([§2](#2-request-rate-limiting))
— and a webhook is the first thing that would make the server *depend on reaching something else*.

## First: offline is a floor on how fast this can ever be

A reading is taken in the field, often with no link. **No webhook can be faster than the moment
the tablet reaches the network** — until then the value exists only on the device and the server
cannot know it. Any promise of "immediate" alerting is a promise this architecture cannot keep,
and saying so before building is cheaper than discovering it afterwards.

**And the instant alert may already exist.** The PWA colours a value against its band *while the
operator is typing it*, offline included. If the need is "somebody must know at once", the person
standing in front of the machine already does — before any server is involved. Establish whether
the real requirement is *that person* acting, or *another system* learning; only the second one
needs any of what follows.

## The trigger: three points, and the earlier one alarms on typos

The request says "when a value is recorded **and** the sheet is approved". Those are different
moments, usually days apart, and hooking the value write would mean storing "this is pending
approval" and re-checking it later. That part of the framing is simply wrong: pick **one** state
transition and scan the entries already stored there, which all carry `max_severity`. No pending
state, one place.

There are three such transitions, and they trade timeliness against truth:

| Trigger | The server knows | What it costs |
|---|---|---|
| **progress** (`POST /api/log-sheets/progress`) | mid-round, as soon as the device has a link | the value is a **draft**. The operator may correct it a minute later, so this alarms on mis-keyed digits |
| **submit** (`submitBatch` / `completeFromWeb`) | when the round is delivered | the earliest point the reading is *claimed* to be true. Not yet reviewed |
| **approve** | after a supervisor signs off | the only "this is true" moment — but hours or days later, which defeats pushing at all |

So the choice follows from *why*, not from taste:

- **Operational alerting** — somebody must act — then **submit**, accepting that it is unreviewed.
  Approval is too late to be worth a push.
- **Feeding another system of record** (a CMMS, a dashboard) — then **approve**, and at that
  latency the polling option below is almost certainly enough on its own.

Two consequences hold whichever is chosen:

- **`unapprove` then `approve` again would fire twice**, and a resubmit would too. The event needs
  a stable identity — (sheet, asset, field, transition instant) or a generated id — and the
  receiver must be told that redelivery is possible.
- **A correction after the fact** (reopen → edit → re-submit or re-approve) changes the values.
  Decide whether that is a new event, an amendment, or nothing.

## Consider not building it

The consumer can already poll `GET /integration/v1/log-sheets?statuses=APPROVED&from=…&to=…` and
read `maxSeverity` from the detail. That answers the stated need with **no new infrastructure, no
outbound network from the plant, and no delivery problem to solve** — the consumer's cursor is its
own business, and a consumer that is down simply catches up.

**This covers the submit trigger as well**, not only approval: the endpoint accepts
`statuses=SUBMITTED,APPROVED`, which is exactly `LogSheetStatus.COMPLETED_STATUSES`. So the
faster of the two useful triggers is pollable today with one changed query parameter.

What polling costs is latency, bounded by the poll interval. If "within a few minutes" is
acceptable — and for a round approved hours after the reading was taken, it usually is — this is
the whole feature, and it is already built.

**Push is worth building when the latency genuinely matters** (an alarm system, a control room
display) or when the consumer cannot poll. Establish which of those it is before writing any of
what follows.

## If push is needed: never call out from the triggering transaction

The one implementation to rule out first. Calling the webhook inline means:

- a slow endpoint makes a supervisor's approval slow — or, on the submit trigger, makes an
  operator's round fail to deliver, which is far worse;
- a failed call has to either fail the transaction — a plant's sign-off, or a tablet's delivery
  of a finished round, must not depend on somebody else's server being up — or be swallowed,
  which is a notification silently lost;
- and a call made before the transaction commits announces something that may then roll back.

**The shape that works is an outbox.** In the same transaction that fires the event, insert rows
into a `webhook_outbox` table: the event, its stable id, its payload, `attempts`,
`next_attempt_at`, `delivered_at`, `last_error`. Nothing leaves the process. A scheduler drains
it — the codebase already runs several ([jobs.md](jobs.md)) — and the round is committed and
complete regardless of whether anything is listening.

Four properties the drain must have, and the third is one this codebase has already paid for:

1. **At-least-once, and say so.** A crash between "sent" and "marked delivered" means a resend.
   The receiver dedupes on the event id; this must be in whatever document the integrator gets.
2. **Backoff, and a give-up.** `attempts` and `next_attempt_at`, with a ceiling after which the
   row is left as failed and visible, not retried forever.
3. **One bad row must not wedge the queue.** Skip it and carry on. The tablet's attachment delete
   queue learned exactly this: a row the server had never heard of stopped the whole pass, and
   every deletion behind it, on every future pass.
4. **Claim rows with a conditional `UPDATE` / `FOR UPDATE SKIP LOCKED`.** It costs nothing now and
   is what makes the drain safe under N replicas without a second pass later — the same shape §19d
   records as already safe for expiry and the import watchdog.

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

**A path to more than one node**, if that is ever wanted. Today it is not, and moving attachments
to a shared store removes one blocker of several rather than the constraint itself — **§19 is the
complete inventory**, and it is longer than this paragraph used to claim: the import subsystem is
actively destructive under N pods, two more directories live on local disk besides attachments,
six places hold per-JVM state, and of the scheduled jobs only three were ever the problem (log
sheet generation has been safe since 2026-10-17). Read §19 before costing any of this.

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

---

# 14. Splitting this into services — **asked for, and declined**

*Raised as: "separate the API layer, the UI layer and the business layer into microservices."
Written down because the question returns, and because the answer rests on facts about this
system that take a day to re-derive. Nothing here is a plan to build; it is a decision not to.*

## First, what was actually proposed is not microservices

Splitting **API / UI / business** is a horizontal cut: it slices the stack by technical layer,
across process boundaries. Microservices are a **vertical** cut — one service owns one business
capability *and its data*, so it can be released on its own.

The horizontal version has a name, **distributed monolith**, and it is the worst of both: every
request crosses two or three network hops to do one thing, no service owns anything end to end,
and nothing can be deployed independently because every change touches all three. The costs of
distribution arrive in full; none of the benefits do.

So the question splits in two, and both answers are no — for different reasons. The horizontal
split is wrong in any system. A *vertical* split would be defensible in principle, and is still
wrong **here**, for the reasons below.

## What makes this particular system a poor candidate

| Fact | Where | Why it resists a service boundary |
|---|---|---|
| A photograph is two writes — the `attachments` row and the id in the reading's `form_data` — and they must land in one transaction | AGENTS.md #123 | Across services this becomes a saga with compensation. The system already paid for getting this wrong **inside one process** |
| The reconciliation takes `log_sheet_entries` with `PESSIMISTIC_WRITE`, because a read-modify-write without a lock loses an id under READ COMMITTED (failed 3 of 3 without it) | AGENTS.md #123 | A database row lock has no cross-service equivalent that is as cheap or as correct |
| Access scope is a **recursive CTE** joined into the query with `EXISTS` | `AssetUnitScopeSql.REPORTABLE_ASSETS_CTE` | You cannot JOIN over HTTP |
| Materialising scope ids in Java and passing them as a filter broke at 5,000 assets, and could not be raised — PostgreSQL's 65,535 bind-parameter limit | AGENTS.md #124, §F7 | **A service boundary forces exactly that shape.** The lesson is already bought and paid for |
| The web panel is server-rendered Thymeleaf sharing the session, `visibleUnitIds()` and the authorities | `web/`, `security/` | "UI as its own service" means shipping the security context over the wire on every page render |
| Offline sync is idempotent per `clientActionId`, one transaction per batch, sheet before attachments, deletions first | `logSheetSync` / `attachmentSync` | The hardest part of the system, and the part that least survives being cut in half |

## The deployment facts

- **Two copies of the same application cannot run yet**, let alone N different ones: the login
  throttle is in memory, the web session registry is in memory (`maximumSessions(1)`), three
  stores sit on the pod's local disk, and the import subsystem's restart recovery would kill
  another instance's running import. **§19 is the full inventory**, with the shape that would fix
  most of it (N stateless pods plus one worker). Horizontal scale-out of *one* service is
  unopened; splitting into many is several steps past it.
- One Windows box, one WinSW service, one `pg_dump`, one log directory, one restore procedure —
  at a site with **no dedicated infrastructure team**. The alternative is N services, service
  discovery, distributed tracing, and N backups whose consistency with each other is now a
  problem somebody owns.
- ~2 cores, ~50 concurrent users, one plant. Network hops add latency; they do not add throughput
  on hardware that is not CPU-bound.
- `attachments` has real foreign keys with `ON DELETE CASCADE`. Splitting the database loses them;
  keeping one database while splitting the code gives every cost of distribution and no isolation.

## The organisational fact, which is the decisive one

Microservices are a solution to a **team** problem — several teams needing to release
independently of one another (Conway's law). This codebase is **328 Java files, ~37,500 lines,
1,923 tests**, maintained by one person. That is a comfortably sized monolith, not a system
straining at its seams.

## What is right in the proposal, and what to do instead

The concern underneath it is usually real: *the code is getting large and tangled.* It is also
accurate about this codebase — packages are organised by **technical concern** (`service/`,
`web/`, `repository/`), so a feature boundary never forms on its own.

The treatment for that is a **modular monolith**, not distribution:

| | Microservices | Modular monolith |
|---|---|---|
| Boundary | process and network | package + a build-failing dependency test |
| Transaction | saga, compensation | the `@Transactional` already there |
| Cost of being wrong | close to irreversible | one refactor |
| Later extraction | — | a well-bounded module *is* the thing you extract |

This is already the decided approach for the next large feature — §13 specifies the same
repository and deployment with an enforced internal boundary, a feature flag, and a test that
fails the build if the dependency direction is violated. The codebase already carries three tests
of that shape (`FieldDataTypesTest`, `LogbackProfileConfigTest`, `FormPatternAttributeTest`), and
**Spring Modulith** packages the pattern if it is ever wanted wholesale.

**Staying inside is reversible; splitting is not.** A well-bounded module can be extracted the day
it needs its own release cadence. A split made today is paid for immediately, against a maybe.

## The boundaries that already exist, and are the right ones

The PWA is its own deployable. The integration API has its own security chain and its own
credentials. Those are vertical boundaries drawn around capabilities — which is what the
technique is actually for.

## What would change the answer

- **Several teams** with genuinely independent release cadences. Not several *layers*.
- A component with a measurably different scaling or availability profile — measured, per
  `performance.md`, not assumed.
- A unit becoming its own product with its own budget (§13 names HSE as the plausible case).

Absent one of those, the work that buys most of the claimed benefit is: an enforced module
boundary, and closing §12's two blockers if a second node is ever actually needed.

---

# 15. Hardening the delivery path — tablet, nginx, and what sits between

*Raised as three worries: a rooted tablet could be made to send data elsewhere; somebody who gets
into nginx could serve a malicious file to the tablets; and — proposed as the remedy — nginx
could hand each tablet a rotating identifier and tell the backend which identifiers are valid.
The first two are real. The third is analysed below and **should not be built**.*

## The topology these answers depend on

| Fact | Where |
|---|---|
| The APK **bundles** its web assets — `webDir: 'dist'`, no `server.url` | `capacitor.config.ts` |
| "The app never talks to the backend directly" — it talks to nginx, which reverse-proxies to Spring Boot. One address, one certificate | PWA `docs/apk.md` §4 |
| The app trusts the system CA store **plus** the bundled plant CA; cleartext is refused | `network_security_config.xml` |
| The browser PWA installs a service worker caching every `js/css/html`, `registerType: 'autoUpdate'` | PWA `vite.config.ts` |
| The backend binds `0.0.0.0:8081` | `application.properties` |
| A token is stateful — `jti` → `api_sessions` row, one device per user, revocable, with `device_label` / `user_agent` / `ip_address` recorded | V1, §8 of security.md |
| The server derives `operational_unit_id` from the submitter; the device never chooses its own scope | `security.md` |

## Worry 1 — a rooted or tampered tablet

**What cannot be prevented, and saying so is the start of the answer:** whatever an operator is
authorised to see, a rooted device in that operator's hands can exfiltrate. Code, memory and
IndexedDB all belong to whoever holds root. No client-side control changes this — and any
credential stored on the device is, by definition, not a secret from that device.

So the goal is **blast radius, integrity of what the server accepts, and revocation** — all three
of which live on the server, and most of which already exist: unit scope keeps a tablet's copy
small; `api_sessions` gives one device per user and instant revocation; the server deriving the
unit means a tampered client cannot file against somebody else's unit; the audit log lives in
PostgreSQL where the device cannot reach it.

What is missing is **rate limiting** (§2): with a stolen token, that is the only thing standing
between an attacker and bulk extraction at wire speed. After that, the controls belong to **device
management** — MDM / kiosk / device-owner provisioning, developer options and unknown sources
disabled, screen lock, disk encryption, remote wipe — not to application code. Root detection and
obfuscation are not worth the effort here: they lose to an actually-rooted device and to a network
with no attestation path.

## Worry 2 — somebody gets into nginx

Real, and its severity depends entirely on which delivery is in use:

| | APK | browser PWA |
|---|---|---|
| Replace the application's code | **no** — it is inside the APK | yes, for everyone, at once |
| Read and alter API traffic (tokens, readings) | yes — nginx terminates TLS | yes |
| Leave something behind after the server is cleaned | no | **yes — the service worker** |

The service worker is the part that is easy to underestimate. It is installed *in the browser*,
it answers subsequent navigations itself, and it therefore **survives nginx being repaired**.
Removing it means visiting each tablet and clearing site data — which also destroys whatever has
not synced. That is the fleet-wide manual sweep §13 calls the riskiest operation this system has.

**So bundling the assets in the APK is a security property, not only an offline convenience.**

In order of value:

1. **Make 8081 unreachable except from nginx.** It binds `0.0.0.0` today. This is also what any
   future "nginx vouches for the caller" scheme would require before it meant anything at all.
2. **Make the served files unwritable by nginx** — read-only mount, or ownership such that the
   web user can only read and a separate deploy step writes. Prevention beats detection.
3. **File integrity monitoring (FIM)** as the net underneath: hash what is served, compare
   periodically, alert on a difference. It converts "serving malicious JavaScript for weeks" into
   "an alert within minutes". Two rules decide whether it is worth anything: the baseline must
   live **off that machine** (an attacker who rewrites both files and manifest gets silence), and
   its scope must stay **tight** — the static root, `nginx.conf`, the TLS key, the service unit.
   A whole-disk FIM produces a wall of findings nobody reads, which is how a gate gets switched
   off (the same reasoning `eslint.config.js` records for its rule set).
   Worth naming explicitly: an intruder often edits **configuration** rather than content — a
   `sub_filter` that injects a script touches no static file, so a FIM scoped only to `dist/`
   is blind to it.
4. **If nginx should not be trusted with plaintext at all**, terminate TLS in the backend and run
   nginx as a TCP/stream passthrough. That is the only option that removes it from the trust set
   rather than hardening it.

## Worry 3 — "nginx issues the tablet an identifier and rotates it" — **do not build this**

Five independent reasons, any one of which is sufficient:

1. **The trust is circular.** It is offered as a defence against a compromised nginx while making
   nginx the *issuer* of the credential. An attacker holding nginx issues themselves a valid one.
2. **It does nothing for worry 1.** A rooted device reads the identifier exactly as it reads the
   JWT.
3. **It authenticates the wrong thing** — "this client spoke to nginx", not "this is an
   authorised tablet" — unless it is bound to a key the device holds, at which point it is mTLS,
   reimplemented worse.
4. **Rotation breaks offline-first.** A tablet offline for a week is the normal case here. An
   identifier rotated while it was away means it cannot sync — precisely the failure this system
   exists to prevent. A rotation period long enough to be safe for offline devices is long enough
   to be worthless as a control.
5. **It is a second authorization model beside `JWT` + `api_sessions`**, which §13 already lists
   as a standing risk: two models drift the first time either is fixed.

And the implementation trap it walks into: "nginx tells the backend this identifier is valid"
becomes "nginx sets a header the backend trusts". With 8081 reachable on the plant network,
anyone can set that header themselves and bypass authentication completely.

**If the goal is "only our tablets may reach the API", the correct forms are:**

| Option | What it gives | Cost |
|---|---|---|
| **Network layer** — a tablets-only VLAN, WPA2/3-Enterprise with per-device credentials | "only our devices", enforced at the layer that owns that question | usually the cheapest; it is network work, not code |
| **mTLS** — a client certificate per tablet, verified by nginx | standard, revocable by CRL, bound to a key, and **survives being offline** because the certificate is long-lived | PKI and enrolment for N tablets; awkward in Chrome on Android, so it suits the APK path far better than the browser one |
| **Device binding inside `api_sessions`** — record a device fingerprint at login and refuse a token presented from elsewhere | the smallest coherent change, inside the model that already exists | modest; no second system |

All three, unlike the proposal, still mean something when nginx is the thing that was compromised.

## Content-Security-Policy — what it does and does not cover

A header (or `<meta>`) telling the **browser** which sources of script, style and — the directive
that matters here — **network connection** are permitted. `connect-src` is what stops injected
code exfiltrating to an attacker's host.

- ✅ Injected script, XSS, a compromised dependency inside the bundle.
- ❌ **A compromised nginx**, because the same nginx serves the header and an attacker removes it.
  This is the same circular-trust error as worry 3, and it has to be said about a control we
  like as readily as about one we do not.
- One genuine exception: a CSP in `<meta>` inside `index.html` is **inside the APK**, beyond
  nginx's reach, so on that path it constrains `connect-src` even against a hostile proxy.

Two costs specific to this codebase, both worth knowing before starting:

- **The server address is editable at runtime** (`SettingsPage.tsx` → `checkServerUrl`). A fixed
  `connect-src` baked into the bundle breaks that feature, and can only be changed by rebuilding.
  Either keep the directive broad enough for the site's network, or accept that each site builds
  its own APK — which `.env.mobile` already implies.
- **The panel has 26 templates with inline handlers and 3 inline `<script>` blocks**, so a strict
  `script-src` needs nonces or the removal of all of them. `'unsafe-inline'` would disable the
  control for the very thing it exists to catch. The first line of defence there is already in
  place and is the stronger one: Thymeleaf's `th:text` escapes by default.

The cheap part can land immediately and breaks nothing: `frame-ancestors 'none'`,
`base-uri 'none'`, `object-src 'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`.
Then `Content-Security-Policy-Report-Only` to find out what a strict policy would break, before
anything is enforced.

## If the APK is dropped and the browser PWA is used alone

Two consequences that are not about security at all, and are easy to discover too late:

1. **Installation on an isolated network.** Chrome's WebAPK is minted on Google's servers at
   install time; a tablet with no route out "silently degrades to a bookmark" (`apk.md` §1).
2. **Which then costs persistent storage.** `navigator.storage.persist()` is granted silently for
   an *installed* PWA and refused for a casual tab (`storageQuota.ts`). Without the grant,
   IndexedDB is evictable, and eviction "takes *everything* — including submitted sheets that had
   not synced yet". That is **data loss**, which outranks all three security worries above.

Plus: NFC works in Chrome via Web NFC but is absent from Samsung Internet and Firefox, silently.
And the service-worker persistence described under worry 2 applies only to the PWA origin — the
Thymeleaf admin panel has no service worker, so browser use of the panel does not carry it.

## The proportion that should govern all of this

None of these three worries is the largest risk the installation currently carries.
`ProductionReadinessRunner` names the larger ones at every single boot: the **JWT secret is still
the value published in this repository** — which lets anyone who can read the repository forge a
token for any user, with no tablet to root and no nginx to compromise — along with the database
password, `CORS: *`, and the bootstrap `admin` / `admin123`.

Order of work, cheapest and largest first:

1. The shipped defaults the readiness check already lists, starting with the JWT secret.
2. Firewall 8081 so only nginx reaches it.
3. MDM / kiosk on the tablets.
4. Rate limiting (§2).
5. Read-only static root, then FIM over it and over `nginx.conf`.
6. The harmless security headers, then CSP in report-only.
7. Only then, if "our devices only" is still wanted: the network layer, or mTLS — never a
   rotating identifier issued by the proxy.

## What would make it urgent

- The plant network gaining a route to the outside, or to an office LAN.
- An audit that asks how a compromise of the web server would be **detected** — which is FIM's
  question, and today the answer is "it would not be".
- A move to browser-only delivery, which promotes worry 2 from "alters traffic" to "replaces the
  application, persistently".

---

# 16. Dependency advisories — what a scan on 2026-10-13 found, and what is worth doing about it

*A full scan of both projects, with the reachability of each finding worked out against this
codebase rather than left as a CVSS number. **Nothing here is applied.** The upgrade is one `pom`
edit plus a test run; it is written down rather than done so the reasoning survives, and so the
next person does not re-derive which of five CVSS-9 advisories actually matter here (none of
them) and which 7.5 does (one).*

> **Findings age.** This is a snapshot of one day. The *method* below is the durable part; rerun
> it before acting, because both the advisories and the fixed versions will have moved.

## How to reproduce it — the part that took the longest

Two obvious tools no longer work unattended:

- `ossindex-maven-plugin:audit` returns **HTTP 401**: Sonatype now requires an account. Worse, it
  logs `Failed to fetch component-reports` as a **WARNING and still reports BUILD SUCCESS** — a
  scan that checked nothing looks exactly like a scan that found nothing.
- `dependency-check-maven` wants an NVD API key; without one it is rate-limited into hours.

What worked, free and unauthenticated:

```bash
mvn -o dependency:tree -DoutputFile=deptree.txt          # 185 artifacts, all scopes
# then per artifact:
curl "https://api.deps.dev/v3/systems/MAVEN/packages/<group%3Aartifact>/versions/<version>"
#   -> {"advisoryKeys":[{"id":"GHSA-..."}]}
curl "https://api.github.com/advisories/GHSA-..."        # severity + the text that decides reachability
cd ../offline-first-pwa && npm audit --json
```

**Validate the method before trusting it.** `org.postgresql:postgresql:42.7.11` returns exactly
`GHSA-j92g-9f8w-j867` — the advisory this project's own `pom.xml` comment names — and `42.7.12`
returns nothing. A scanner that cannot reproduce a result you already know is not evidence.

## Backend — 185 artifacts, 0 lookup failures, 7 carrying advisories

| Artifact | Version | Worst CVSS | Count | Reachable here? |
|---|---|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.22 | **9.8** | 3 | **No** |
| `org.springframework:spring-webmvc` | 7.0.8 | **9.8** | 2 | **No** |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.4 | 7.5 | 9 | **Yes — pre-authentication** |
| `tools.jackson.core:jackson-databind` | 3.1.4 | 7.5 | 7 | yes, same path |
| `com.fasterxml.jackson.core:jackson-core` | 2.21.4 | 7.5 | 2 | yes, same path |
| `tools.jackson.core:jackson-core` | 3.1.4 | 7.5 | 2 | yes, same path |
| `org.apache.logging.log4j:log4j-api` | 2.25.4 | — | 1 | **No** |

The two advisories this project patched by hand are confirmed clean: `postgresql:42.7.12` and
`poi-ooxml:5.4.0`. The comments in `pom.xml` were right.

### Why the five CVSS-9 findings do not apply

**Tomcat, all three** (`GHSA-9xv2-5v5q-p794` DIGEST authenticator, `GHSA-h3x4-894j-xpx5` FORM
authentication, `GHSA-gcx9-497g-6cp6` security-constraint bypass) are about **container-managed**
authentication and `<security-constraint>`. This application has **no `web.xml` at all** and no
container-managed security: every rule lives in Spring Security's filter chains. A grep for
`Digest` in `src/main/java` returns three hits and all three are `MessageDigest` — the SHA-256 in
`ApiKeyCredentials` and `AttachmentStorageService`, not an authenticator.

**Spring, both** need a feature this application does not have. `GHSA-j9f9-w8pj-32f8` is Server-Sent
Events with view fragments — `SseEmitter` / `text/event-stream` appear **zero** times.
`GHSA-pc63-qcmh-9cmg` is `XsltView`, which appears **zero** times and additionally requires a
`/**` mapping that renders a view with no explicit view name; this panel renders Thymeleaf.

**log4j-api** (`GHSA-qv9r-c865-cp47`) concerns encoding non-finite floats while writing a
`MapMessage` as JSON. Logging here is Logback; `log4j-api` arrives only as a POI dependency and
is bridged out by `log4j-to-slf4j`. Nothing writes a `MapMessage`.

### Why the 7.5 findings are the ones that matter

`/api/auth/login` is **`permitAll`** (`WebSecurityConfig`), and `@RequestBody LoginRequest` means
**Jackson parses an untrusted body before any authentication happens**. Three things compound:

- there is **no size limit on a JSON body** — `spring.servlet.multipart.*` bounds multipart only,
  and Tomcat's `maxPostSize` governs form encoding, not `application/json`;
- **request rate limiting is not built** (§2);
- several of these advisories are exactly denial-of-service by parser — `GHSA-p6pp-m3f8-5c89`
  (quadratic backtracking in `NumberInput.PATTERN_FLOAT`), `GHSA-7hhh-6rmp-j9qf`
  (`_reportInvalidToken` with no `maxErrorTokenLength`), `GHSA-cxp5-3px4-pw24` (quadratic
  forward-reference completion), `GHSA-q4xh-88c3-wmh7` (unbounded `Duration` parse).

So the lowest-scoring group is the only one an unauthenticated caller on the plant network can
reach. **This is the finding of the scan**, and the reason a CVSS ranking alone would have pointed
at the wrong four dependencies.

### The fixed versions, verified one by one — and why Boot 4.1.1 is not enough on its own

`4.1.1` is the newest stable 4.1.x (after it, only `4.2.0-M1`/`M2`, which are milestones). It
raises every affected line, but **only Spring all the way to a clean version**:

| Line | Here | Boot 4.1.1 manages | First clean version |
|---|---|---|---|
| `spring-framework` | 7.0.8 | **7.0.9** ✅ | 7.0.9 |
| `tomcat` | 11.0.22 | 11.0.24 — still all three | **11.0.25** |
| `jackson-2-bom` | 2.21.4 | 2.21.5 — five remain | **2.21.7** |
| `jackson-bom` (Jackson 3) | 3.1.4 | 3.1.5 — five remain | **3.1.7** |
| `log4j2` | 2.25.4 | unchanged | **2.25.5** |

Each "first clean" was established by querying that exact version and getting an empty
`advisoryKeys`, not by reading a changelog.

The change, in the style `postgresql.version` already uses in this `pom.xml` — parent bump plus
four explicit overrides, each one a deliberate statement rather than an inherited default:

```xml
<parent>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
</parent>
...
<tomcat.version>11.0.25</tomcat.version>
<jackson-2-bom.version>2.21.7</jackson-2-bom.version>
<jackson-bom.version>3.1.7</jackson-bom.version>
<log4j2.version>2.25.5</log4j2.version>
```

Overriding a BOM-managed version is not free: it is the project taking responsibility for a
combination Spring Boot did not test together. These are patch releases inside the lines Boot
already manages, so the risk is small — but `mvn -o test` (1,923 tests) is what settles it, and
a live run after, per `CLAUDE.md`.

## PWA — 22 npm findings, none of them in what reaches a tablet

Three critical and nine high, and **every one is build tooling**: `vitest` / `@vitest/mocker`
(`GHSA-5xrq-8626-4rwp`, `GHSA-82fw-gwwq-j7x9`), `vite`, `esbuild` (`GHSA-67mh-4wv8-2f99`), `tar`
via `@capacitor/cli`, `sharp` via `@capacitor/assets`.

The two that look like runtime dependencies are not:

- **`uuid`** — `GHSA-w5hq-g745-h8pq` affects `<11.1.1`; `npm ls` shows the application's own
  `uuid` **is 11.1.1**, already fixed. The vulnerable `7.0.3` sits under
  `@capacitor/cli → xcode` — a dev dependency, and the **iOS** tooling, which this project does
  not use. The advisory also concerns v3/v5/v6 called with a `buf` argument; the codebase imports
  `v4` only.
- **`react-router` 6.30.6** — `GHSA-wrjc-x8rr-h8h6` is `deserializeErrors()` during **SSR
  hydration**. This is a client-only SPA: `renderToString`, `hydrateRoot` and `StaticRouter`
  appear zero times.

**The shipped bundle is clean**, which is worth stating plainly because a bare "22 vulnerabilities,
3 critical" from `npm audit` says the opposite.

That does not make the dev findings harmless — they are about **the build machine**: the `esbuild`
one lets any website the developer visits read responses from the dev server, and the `vitest` one
is a path traversal. `npm audit fix` without `--force` closes part of it.

## The real gap: nothing scans automatically

Both sets of findings had been sitting there, and nothing in the build or in CI would ever have
mentioned them. Whatever is chosen, it has to satisfy two constraints this project already has
opinions about: it must not **fail the build on a day the advisory database moves** (a gate that
fires on something nobody changed gets switched off — the reasoning `eslint.config.js` records),
and it must not need an account or a key that only one person holds.

A scheduled job that runs the `deps.dev` loop above and reports — rather than a build step that
breaks — is the shape that survives. `dependency-check-maven` with an NVD key, or Dependabot if
this repository ever reaches a host that offers it, are the heavier alternatives.

## What would make it urgent

- **Any of the Jackson advisories gaining a public exploit**, because that path is
  pre-authentication and unthrottled — or, equivalently, §2 staying unbuilt while the plant
  network gains a route to anything wider.
- A configuration change that makes one of the unreachable five reachable: adding SSE, adding a
  container-managed security constraint, or introducing `XsltView`. Each is individually unlikely;
  together they are the reason to upgrade anyway rather than to rely on the analysis above
  staying true.
- An audit asking for a dependency report, which today would have to be produced by hand.

---

# 17. Serving the panel over TLS, and closing port 8081

*From the production review of 2026-10-13. Two findings that share one decision and are therefore
one section. **Neither is built.** The other four findings from that review were fixed in the same
commit — the JSON body ceiling, the nightly audit purge, the dependency bump — or already shout
for themselves at every boot; these two could not be done without first answering a question only
the site can answer.*

## The fact that reframes both

The topology is **mixed**, and that was not obvious until it was asked about directly:

- **tablets / PWA** → nginx (HTTPS) → backend on 8081 (plain HTTP)
- **the admin panel** → the backend on 8081 **directly**, over plain HTTP

And the backend speaks no TLS of its own — there is no `server.ssl.*` anywhere in
`application.properties`; it binds `0.0.0.0:8081` as cleartext HTTP.

So the first thing to write down is not the cookie flag. It is this:

> **An administrator's password and session cookie cross the plant network in clear text.**
> Not a flag that is missing — the whole exchange is readable by anything on the path, and a
> captured `JSESSIONID` is an administrator session with no password needed.

That outranks everything else in this section, and it is what the decision below has to fix.

## What was measured, on a running instance

```
$ curl -D - http://localhost:8081/login
Set-Cookie: JSESSIONID=…; Path=/; HttpOnly          ← no Secure, no SameSite
X-Content-Type-Options: nosniff                      ← Spring Security's defaults are present
X-Frame-Options: DENY                                   and correct
Cache-Control: no-cache, no-store, must-revalidate
                                                     ← no Strict-Transport-Security

$ curl -D - -H "X-Forwarded-Proto: https" http://localhost:8081/login
Set-Cookie: JSESSIONID=…; Path=/; HttpOnly          ← unchanged: the header is ignored
$ curl -D - -H "X-Forwarded-Proto: https" -H "X-Forwarded-Host: 192.168.0.100" \
       http://localhost:8081/users
Location: http://localhost:8081/login                ← wrong scheme AND an address no tablet
                                                        can reach
```

`server.forward-headers-strategy` is unset, so Spring never looks at the forwarded headers and
believes every request arrived as plain HTTP from the proxy. Three consequences follow from that
one setting: no `Secure` on the session cookie, no HSTS, and redirects built with the wrong scheme
and host. nginx can paper over all three (`proxy_cookie_flags`, `add_header`, `proxy_redirect`) —
but its configuration is not in this repository, so nobody reviewing this project can confirm it,
and the admin path does not go through nginx at all.

## Two traps, if the setting is simply switched on

**`cookie.secure=true` would lock administrators out.** A browser does not send a `Secure` cookie
over `http://`. An administrator connecting directly to `http://…:8081` would log in, receive the
cookie, and arrive at the next request unauthenticated. This cannot be enabled while the panel is
served over plain HTTP — which is the real argument for fixing the transport rather than the flag.

**Forwarded headers are only as trustworthy as who can send them.** With 8081 reachable from the
plant network, anyone can set `X-Forwarded-For` themselves. Worth being precise: **this is already
true today.** Four places read that header directly — `AuthApiController`, `RequestMdcFilter`,
`IntegrationApiKeyFilter`, `WebSessionMetadataStore` — and write it into `api_sessions.ip_address`
and the audit trail. The IP in those records is forgeable now, before anything here changes.

If the setting is adopted, `native` is the safer value than `framework`: it uses Tomcat's
`RemoteIpValve`, which has a notion of trusted proxies, and that list must be narrowed to nginx's
address — its default covers the whole private range, which is the same network an attacker would
be on.

```properties
server.forward-headers-strategy=native
server.tomcat.remoteip.internal-proxies=<nginx's address, exactly>
```

## The decision, which makes most of this disappear

**Does the admin panel also go through nginx?**

- **Yes** — then one address, one certificate, one place to configure TLS. `forward-headers-strategy`
  with a narrowed proxy list, `server.servlet.session.cookie.secure=true`, `same-site=Lax`, HSTS,
  correct redirects — and **8081 can be firewalled to nginx alone**, which closes the forged-header
  problem and the bypass-the-proxy problem in the same move. This is the recommended answer.
- **No** — then the panel still has to reach HTTPS some other way, either by enabling TLS on the
  backend directly (`server.ssl.*`) or on a second nginx route. Only after that can the cookie be
  marked `Secure`. 8081 can then at best be restricted to the administrators' subnet, not closed.

Until one of those happens, the `Secure` flag is the least of that path's problems and should not
be set in isolation.

## The other half: nothing limits how often

Request rate limiting (§2) is still unbuilt, and the review gave it a sharper edge than it had.
`/api/auth/login` is `permitAll`, and the JSON body ceiling added in this same commit
(`app.http.max-anonymous-json-body-bytes`) bounds how *large* one unauthenticated request can be —
but nothing bounds **how many**. `LoginAttemptService` locks a username after five failures; it
does not slow down a caller who sends ten thousand requests naming ten thousand different
usernames, and nothing at all throttles an authenticated tablet with a valid token.

Firewalling 8081 narrows who can reach it to the plant network; it does not make the plant network
small.

## What would make it urgent

- **The panel being used from outside the plant network**, by VPN or any other route — at which
  point cleartext administrator credentials stop being a local-network risk.
- Any audit that asks how administrative access is protected in transit; "it is not" is the
  current answer.
- A second application node (§12), which forces the proxy question anyway.

## And the standing reminder, which belongs with these

These two sit alongside the settings `ProductionReadinessRunner` has been naming in a bordered
block at **every single boot** — still true as of this review:

| Setting | Still shipping as |
|---|---|
| `APP_AUTH_JWT_SECRET` | the value published in this repository — anyone who can read it can forge a token for **any** user, with no tablet to root and no proxy to compromise |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` |
| `APP_CORS_ALLOWED_ORIGINS` | `*` (confirmed live: `Access-Control-Allow-Origin: *` on `/api/**`) |
| `APP_AUTH_LDAP_TRUST_SELF_SIGNED` | `true` — §the pinned-truststore route exists now, see the README |
| bootstrap admin | `admin` / `admin123` |

They are deliberately left to the site rather than changed here, and they are recorded in four
places now: that boot-time block, the README's "Before production" table, `deployment.md`, and
this line. **The JWT secret is the one that makes every other control on this list irrelevant.**

---

# 18. A read-only review of 2026-10-15 — fourteen findings, nine of them now fixed

*The review that produced this section changed nothing: the whole of it was read out of the code
on the commit named below. **Eight have been fixed since** — 18d, 18h and 18i on 2026-10-15, then
18a, 18c, 18e, 18f and 18g on 2026-10-17 — and their subsections here are now pointers to the
reference documents that describe the behaviour, which is the rule this file opens with. **18b,
18j, 18k, 18l and 18m are still true of the current tree**, each for a stated reason.*

Reviewed at `1437429`, a clean tree on `main`, with 1,944 tests green. The review looked at the
four authentication surfaces, the mobile submit and progress paths, the audit aspect, the panel's
pages, the reports and the templates. Three findings sharpen sections that already exist (§2,
§16, §17) and are cross-referenced rather than restated. **18l and 18m were added afterwards**,
from a second pass that asked what else could hurt a plant rather than what the code gets wrong;
18l is the most serious thing in this section.

**One claim in the first version of this section was wrong, and the correction is worth more than
the finding was.** 18i said a 10,000-row asset import paid 10,000 extra connections and SELECTs.
It pays none: that import is all inserts of a generated-id entity, and the aspect returns before
the read when the id is still null. What does pay per row is an insert of an **assigned or
composite id** entity, which reads and always finds nothing. The measured version is in
[performance.md §4f](performance.md).

**What was checked and found sound** is worth recording too, because it is the part a later
review should not pay for twice: there is no `th:utext` anywhere in the 51 templates, so the
panel has no unescaped-output surface; no repository query is built by string concatenation (the
one `+` in `AssetStatusChangeRequestRepository` joins two compile-time constants); the Excel
export writes string cells through POI, so a cell opening with `=` is text and not a formula;
`passwordHash`, `password` and `secretHash` are all in `AuditEntitySupport.MASKED_FIELDS`, which
covers every secret column the schema has; attachment keys are confined to the storage root by
`normalize()` + `startsWith()` and the MIME type is sniffed from magic bytes rather than trusted
from the upload; every `Optional.get()` in `src/main/java` is preceded by its own emptiness
check; there is not one empty `catch` block; and `LoginAttemptService` keys its map on the
username **of a user that exists** (`AppAuthenticationProvider:88`, `:98`), so an unauthenticated
caller cannot grow it — the one shape that would have made §2 urgent today.

## Priority for a production deployment

Asked directly on 2026-10-15: of everything here, what matters for a plant that is about to run
this. The honest answer begins outside this section.

**Nothing in §18 is a reason to delay a deployment that is otherwise ready.** Two things that are
not in §18 are.

### Before go-live, and none of it from this review

1. **`APP_AUTH_JWT_SECRET`.** It ships as the string published in this repository. Anybody who can
   read the repository can mint a token for any user, with no tablet to root and no proxy to
   compromise — and that makes every other control on this list, and most of §18, irrelevant. It
   is one environment variable.
2. **The other four shipped defaults** — the database password, `APP_CORS_ALLOWED_ORIGINS=*`,
   `APP_AUTH_LDAP_TRUST_SELF_SIGNED=true`, and the bootstrap admin's `admin123`. The first three
   are environment variables; the fourth is a password change at first login.
   `ProductionReadinessRunner` names four of the five in a WARN block at **every** boot — the
   bootstrap admin is the one it cannot see, because that one is a database row rather than a
   property, so nothing will ever remind anybody about it.
3. **§17's question, which only the site can answer**: does the admin panel also go through
   nginx? Today it reaches 8081 over plain HTTP while the tablets get TLS, so administrator
   credentials and session cookies cross the plant network in cleartext. Answering "yes" closes
   that, makes the session cookie `Secure` meaningful, and lets 8081 be firewalled to nginx
   alone — which is also what fixes 18b.

That is the whole of the go-live list. Everything below is this review's own, ordered by what a
plant would actually feel.

### Worth fixing before go-live, from this review

- **18d — done.** It was the one finding here that could be wrong on day one rather than wrong
  under load, and it no longer depends on the host's zone at all. Nothing to check on the host.
- **18a — done**, along with 18c, 18e, 18f and 18g. The control the 2026-10-13 review added is
  doing its job again, and the batch no longer has a head-of-queue failure mode.
- **18l — done**, and measured before and after with two instances against one database. The
  operational rule still has to be kept: one instance per database, the service stopped before
  the JAR is replaced, no manual run left pointing at the production database. What the fix
  removes is the *silent* failure, not the reason for the rule.

### Only worth doing together with the decision they belong to

- **18b** waits for §17. Setting `forward-headers-strategy` before 8081 is closed makes a forged
  header easier to supply, not harder. **Still open, and deliberately so.**
- **18h — done**, ahead of §2 rather than with it, because the fix is self-contained and the
  oracle it closes is what makes the username-keyed lockout targetable. §2 is still what bounds
  how often any of it can be asked.
- **18i — the defect is done**, and what is left is the free measurement: watch
  `hikaricp.connections.pending` on `/actuator/metrics` during the next large Excel import. If it
  stays at zero, the remaining cost is a cost rather than a risk and can stay written down.

### Housekeeping, any time, in any order

**18c**, **18g**, **18f** — each is a few lines and none of them changes what the system does
today. **18e** is also small, and is the one in this group worth doing before the fleet grows,
because its symptom (a tablet whose queue never drains) is read as a sync bug and costs a day
before anybody looks at the server. **18j** and **18k** are not fixes yet, they are measurements:
take them when the user table or the log-sheet table has grown enough for the numbers to mean
something.

## 18a. The anonymous JSON ceiling's path matching — **built**

Fixed on 2026-10-17. The filter compared the raw `getRequestURI()` to the configured paths with
`equals`, so `POST /api/auth/login;x=1` — still `permitAll`, still routed to the login handler —
was measured against the 64 MB authenticated ceiling instead of the 64 KB anonymous one.
`/api/auth/login/`, `//api/auth/login` and `/api/auth/%6Cogin` were the same bypass in three other
spellings.

It now reduces the path the way Spring's matchers do rather than enumerating spellings: each
segment cut at its first `;`, empty segments dropped, each segment percent-decoded, a malformed
escape treated as unreadable. Every reduction can only move a request to the **tighter** ceiling,
and the configured paths go through the same reduction so a property written with a trailing slash
still names its endpoint.

Where it lives now: [security.md §9](security.md), the «JSON body ceiling» row, and AGENTS.md #139
for why the raw URI was the wrong string to compare. Pinned by `JsonBodySizeLimitFilterTest` —
the four bypass spellings, the malformed escape, a configured path with a trailing slash, and the
counterweight that an authenticated path keeps its loose ceiling whatever its spelling.

**It changes nothing about §2.** A 64 KB ceiling on an unbounded number of requests is still a
ceiling on one request.
## 18b. `X-Forwarded-For` is believed, and 8081 is reachable without a proxy

Four places read the header and fall back to `getRemoteAddr()` only when it is absent —
`IntegrationApiKeyFilter:165`, `WebSessionMetadataStore:54`, `AuthApiController:75`,
`RequestMdcFilter:63` — and nothing sets `server.forward-headers-strategy`, so Spring is not
deciding which proxies may speak for a client either.

That is sound while every caller arrives through nginx. It is not sound today, because §17
establishes that **the panel reaches 8081 directly** and that the port is not firewalled to the
proxy: any caller who can reach 8081 can name their own address in the four records above.

What that costs is exactly the control `security.md` §7 claims: "every request is recorded,
including the refusals". The row is still written; its `ip_address` is the caller's choice. The
same is true of the address on a web session and of the IP in the log line for a login.

**It is one decision with §17, not a separate fix.** Firewalling 8081 to nginx makes the header
trustworthy and `forward-headers-strategy=framework` correct in the same move; setting the
strategy *before* the port is closed only makes a forged header easier to supply. Until then the
honest statement is that these four addresses are hints, not evidence.

## 18c. The chunked-body guard and `getReader()` — **built**

Fixed on 2026-10-17. `LimitedBodyRequest` overrode `getInputStream()` only, and
`HttpServletRequestWrapper.getReader()` delegates to the wrapped request — straight past the
counter. Nothing read a body that way (Spring's JSON path and `StringHttpMessageConverter` both go
through `getBody()`, which is `getInputStream()`), so this was latent rather than exploitable. It
is closed because this class is what the next filter-level body limit will be copied from.

`getReader()` now wraps the limited stream, with the declared charset and a UTF-8 fallback for a
header a caller controls. Pinned by `JsonBodySizeLimitFilterTest`: a chunked body past the ceiling
fails through the reader, and one under it reads normally.
## 18d. The twelve-month trend's months — **built**

Fixed on 2026-10-15. `ReportWebController` passed `ZoneId.systemDefault()` into `monthlyTrend`,
so on a UTC host every month boundary of «داشبورد مدیریتی»'s trend sat 3.5 hours early: a round
raised at 02:00 Tehran on the first of a month was counted in the previous month, and for the
first 3.5 hours of each month the current month had no column at all.

It now passes `DateUtils.TEHRAN`, and that constant — made public, with the reasoning on it — is
the only place in the application that names a zone; `LogSheetGenerationService` and
`LogSheetTemplateService` read it instead of repeating the literal. `app.integration.default-zone`
stays configuration, because an integrator chooses their own zone.

Where it lives now: [reports.md §1](reports.md). Guarded by `PlantZoneIsPinnedTest` (no production
code reads the host's zone, and the zone is named exactly once) and
`ManagementReportTrendZoneTest` (the twelve windows are Tehran midnights, and the twelfth is the
current Tehran month).
## 18e. A batch's successes and one item that throws — **built**

Fixed on 2026-10-17. The transaction boundary moved from around the loop to **around the item**,
for both `POST /api/log-sheets/batch` and `POST /api/log-sheets/progress`. A thrown exception is
now that item's `ERROR` — an outcome this endpoint already returned and the deployed PWA already
parks with its message, so **no client change was needed** — and the other items commit.

The reasoning, and the invariant it rests on, are in
[jobs.md §4](jobs.md#the-mobile-sync-batch): a batch is a transport optimisation, not a unit of
work, so cross-item atomicity never had a meaning; but the propagation is `REQUIRED`, so **nothing
may wrap the call in a transaction** or the isolation silently disappears. AGENTS.md #138 records
why `REQUIRES_NEW` — the propagation that looks safer — is the wrong answer, and how the test
suite proved it.

Also recorded: one commit per item instead of one per request is a real cost, accepted with its
reasons and the thing to watch, in [performance.md §4g](performance.md).
## 18f. Two concurrent progress pushes and the `START` row — **built**

Fixed on 2026-10-17. The decision to write a round's one `START` row came from a field read a
moment before the atomic update, so two devices signed in as the same operator could both read
`started_at` as null and both append a row. `started_at` itself was never at risk.

The right to write it is now claimed by an update carrying `started_at IS NULL` beside the
ownership and status guards: whoever it returns 1 to is the first reporter, and nobody else can
be. It runs only when the caller's own read already said the round had not started, so every push
after the first costs nothing extra.

Where it lives now: [log-sheets.md §2](log-sheets.md), the action-log section. Pinned by
`LogSheetProgressSyncIntegrationTest` — the claim succeeds exactly once, and somebody who does not
hold the sheet cannot stamp its start — beside the existing end-to-end test that one round
produces one `START`.
## 18g. An empty `APP_CORS_ALLOWED_ORIGINS` — **reported, deliberately not corrected**

Handled on 2026-10-17, and the handling is a warning rather than a fix, which was the plant's
call: an empty value produces an empty allow-list, which refuses every cross-origin caller. That
is correct while the PWA is served same-origin behind nginx, and it is the behaviour a running
plant already has — so changing it would be a surprise in the one direction nobody asked for.

What it also does is strip the CORS headers from the 401, so a tablet served from another host
cannot tell an expired session from a server it cannot reach. `ProductionReadinessRunner` now says
so at **every boot**, beside the four shipped defaults it already names, which is where somebody
setting the system up will meet it.

Where it lives now: [jobs.md § Production readiness check](jobs.md). If the PWA is ever served
from another origin, set it — that is the moment this stops being a note and becomes a defect.
## 18h. The login timing oracle — **built**

Fixed on 2026-10-15. A username nobody holds now costs the same password comparison as one that
exists: `retrieveUser` compares the presented password against a dummy hash before letting the
`UsernameNotFoundException` through, and logs that it did. A **deactivated** account pays the same
comparison in the same place — Spring's `preAuthenticationChecks` refuses it before
`additionalAuthenticationChecks` is reached, so the `isActive` guard in there is dead code
(AGENTS.md #136), and left alone a deactivated account would have become the only fast answer
left, which is the same oracle one account at a time.

The dummy hash is encoded through the configured `PasswordEncoder` at startup rather than written
as a literal, so it carries whatever cost that encoder is set to. The comparison's result is read
rather than discarded, because a comparison whose outcome is unused is one a compiler may remove.
A **locked** account still discloses itself, deliberately: it is told how long it has to wait.

Where it lives now: [security.md §9](security.md), the «Login timing» row. Pinned by
`AppAuthenticationProviderAuthTest` — the absent username, the deactivated account, and that a
username on its way into a log line cannot forge a second line.

**This does not close §2.** The oracle is gone; what is still missing is any bound on how often
the endpoint may be asked.
## 18i. The audit trail's second connection — **built, in the part that was a defect**

Fixed on 2026-10-15, and the finding was partly wrong — see the correction above.

**The defect**: an exception inside the pre-change read came out of the aspect as *the caller's*
exception, so a connection the pool could not supply turned a supervisor pressing save into an
error page, over an audit row nobody had asked for. The capture is now wrapped; the write
proceeds, a WARN names the entity type and id, and **no audit row is written** — not a row
missing its previous values, because an UPDATE recorded as a CREATE is a confident falsehood in
the one table whose purpose is to be evidence (`PriorState.unknown()` is deliberately distinct
from `PriorState.create()`).

**The deadlock risk is now bounded** rather than eliminated: `app.audit.snapshot.max-concurrent`
(4) caps how many of these reads hold a second connection at once, and
`app.audit.snapshot.wait-ms` (5 s) bounds the wait before one gives its audit row up. Unbounded,
every connection in the pool could be held by a request waiting for a second one.

**The second connection itself stays, by construction.** The committed row cannot be read from
the current persistence context — after an auto-flush, the managed instance, Hibernate's
`loadedState` and the row this transaction can see all hold the new values — so only a second
transaction can answer the question. Every way of removing the read trades correctness for cost,
and that trade is refused; the alternative of holding one entity manager for the whole
transaction was also refused, because a connection held for the length of a long import is worse
for the pool than many short borrows.

**What remains open is a measurement, not a fix**: `hikaricp.connections.pending` on
`/actuator/metrics` during a large import. If it stays at zero, this is a cost and not a risk.

Where it lives now: [jobs.md § The pre-change read](jobs.md) and
[performance.md §4f](performance.md). Pinned by `RepositoryAuditAspectSnapshotTest`.
## 18j. `/operational-units` loads every user, on every render, into the page

`OperationalUnitWebController:65` calls `userService.findAll()` and `:94` turns the whole result
into `userPickerItems`, which the template inlines. The page needs two things from it — names for
the supervisor and operator columns of the rows on *this* page, and the picker's options — and
takes the whole user table for both, as hydrated entities.

Every other picker in the panel does the opposite: `/options/assets`, `/options/locations`,
`/options/plant-systems`, `/options/main-functions` and `/options/operational-units` search
remotely as the administrator types (`remote-select.js`, `remote-multi-select.js`). This is the
one page left that answers "which users exist" by sending all of them.

At a few hundred users it is invisible, which is why it has survived; it is linear in the user
table forever, in query time, in heap, and in the HTML the browser parses before first paint —
and §3d of `performance.md` is the record of what that last one costs when it grows unnoticed.
The page is already in the measured table at **5 scans / 4 rows**, so the scan count is not the
problem; the row count behind one of those scans is.

## 18k. The landing page is the one page never measured

`DashboardController` issues seven unscoped `count(*)` queries — locations, asset classes, assets,
log sheets, sub-functions, users, operational units (`DashboardController:28` onwards) — and `/`
is absent from `performance.md` §2, which measures every other list page in the panel.

Six of the seven count master data, which is bounded by the plant. The seventh counts
`log_sheets`, which grows by ~3,650 rows a year and is never pruned, and an unqualified
`count(*)` in PostgreSQL reads every visible row, or every index entry, each time. At a decade of
history that is tens of thousands of rows per page view, which is still milliseconds — the point
is that the number is unknown and the page is the one everybody lands on.

The counts are also plant-wide for a unit-scoped viewer, which is the same decision as
`security.md` §F1 and is accepted there; it is noted here only so a later reader does not read
this section as disagreeing with it.

## One more thing this review found, which is not a finding

`api_sessions` has **no retention of any kind**: no purge job, no `deleteBy…`, nothing. One row
is written per mobile login and kept forever. `audit_log` got a nightly purge in the 2026-10-13
commit and `api_key_usage` has had one since V3, so this is the last of the three append-only
operational tables without one. At fifty tablets and three shifts it grows by roughly 55,000 rows
a year, every lookup goes through `uk_api_sessions_jti`, and the admin page pages in the database
— so there is nothing to fix today, and the number is small enough that a purge would be
housekeeping rather than relief. It is recorded so that the next person who asks "which tables
grow without bound" gets the complete answer: this one, `log_sheet_action_log` and
`log_sheet_entry_revisions`, and the last two are business history and are supposed to.

## 18l. A second instance generating every round twice — **built**

Fixed on 2026-10-17, and this one was **observed before it was fixed** rather than reasoned about.

**The measurement.** Two instances against one database, a template pushed sixty hours overdue
with `APP_SCHEDULER_LOG_SHEET_MAX_BACKFILL=60` so their ticks overlap for certain, occurrences
compared by intersecting what each instance logged:

| | occurrences generated by each | the same occurrence in both | sheets per occurrence |
|---|---|---|---|
| before the fix | 20 and 20 | **all 20** — ids interleaved 89, 90, 91, 92 … | two |
| after the fix | 60 and 60 | all 60 | **one**, plus one WARN |

After the fix both instances still do the work — nothing coordinates them up front — and the
loser's transaction rolls back every round it created, sixty of them in the measured run. What
reaches the log is one line, at WARN, naming the template and saying that more than one instance
is running.

**The mechanism** is the tick's last statement: `advanceCursorIfUnchanged(id, cursorWhenRead,
boundary, now)`, a conditional `UPDATE` whose row count decides whether this tick's work stands.
`runScheduled` is already one transaction per template with generation inside it, so a row count
of 0 becomes `TemplateCursorMovedException` and the rounds disappear with the rollback. No lock,
no migration, no schema change — and the cursor comparison is null-safe for a template whose
schedule has never run.

**It also closed an unnoticed lost update.** The tick receives a detached template, so
`templateRepository.save(template)` was a `merge` that wrote every column back from a snapshot
seconds old — reverting any edit made to the same template while the tick ran. The replacement
writes two columns.

**The one mistake that would have been catastrophic** is comparing against the advanced boundary
instead of the cursor as read: every tick would lose its own race, every transaction would roll
back, and no round would ever be raised again — silently, because losing is only a WARN.
`LogSheetGenerationServiceTest.theCompareAndSetUsesTheCursorAsItWasRead` exists for exactly that,
beside a test that the template is never saved wholesale any more, and
`ExplicitTemplateAssetIntegrationTest` pins the SQL's own semantics (one winner, the null-cursor
case, a mismatched expectation changing nothing).

Where it lives now: [jobs.md § What a second instance does](jobs.md) and
[deployment.md](deployment.md). **The operational rule has not changed** — one instance per
database, the service stopped before the JAR is replaced, no manual run left pointing at the
production database. Everything else a second instance does is still unguarded; this fixes the
one failure that was both silent and wrong.
## 18m. Attachment bytes are buffered in heap, not streamed

The README's heap advice used to justify a small heap partly on the grounds that "attachment
bytes stream to and from disk rather than being buffered in memory". They do not:
`AttachmentStorageService.store(String, byte[], String)` takes the whole file as a `byte[]`,
`read` is `Files.readAllBytes`, and `AttachmentController.download` returns
`ResponseEntity<byte[]>` — so an upload holds the entire file in heap once and a download holds
it twice while the response is written.

**The number the README gives was right and the reason was wrong**, which is the more dangerous
combination: with the shipped ceilings (25 MB per file, 20 MB video, 50 MB multipart request) ten
concurrent video downloads are a few hundred megabytes of short-lived garbage and `-Xmx2g`
absorbs it without noticing. The sentence is corrected, and the arithmetic now sits beside the
`-Xmx` table so that **raising `APP_ATTACHMENTS_MAX_VIDEO_BYTES` is visibly a heap decision**.

Streaming is the unbuilt part, and it is deliberately unbuilt: `store` hashes the content
(`sha256Hex`) and sniffs its magic bytes before writing, and `AttachmentService` compares that
hash against existing rows to de-duplicate — all of which want the bytes in hand. Streaming would
mean writing to a temporary file first and hashing as it goes, which is a real change to the
upload path for no benefit at the current ceilings.

### What would make it urgent

- **A ceiling raised past about 50 MB**, or a media type added that is larger by nature.
- **Heap cut below 2 GB** on a host that also serves attachments — the two decisions are now
  linked in the README, which is the point.
- Sustained concurrent downloads of large video, which would show as GC pressure rather than as
  an error: watch `jvm.gc.pause` and `jvm.memory.used` on `/actuator/metrics` before concluding
  anything from a report of "the panel feels slow".

## 18n. A wrong HTTP method answers 302 live, and 405 in the suite

Found on 2026-10-17 by running the application rather than the tests, which is the only way this
class of defect is ever found here.

```
PUT /api/log-sheets/batch   with a valid bearer token
  → HTTP/1.1 302          Allow: POST
GET /error
  → HTTP/1.1 302          Set-Cookie: JSESSIONID=…
```

The `Allow: POST` header is the tell: Spring **did** detect the method mismatch and **did** set
405. What happens next is the container's ERROR dispatch to `/error`, which re-enters the security
chains — and `/error` is not permitted on the web chain, so the final answer to the caller is a
redirect to `/login`. `ApiExceptionHandler` never sees the exception, because a method mismatch is
thrown before any handler method is selected and a controller-scoped advice has no controller to
apply to.

**Why the suite says otherwise.** `ApiClientErrorStatusIntegrationTest` asserts
`isMethodNotAllowed()` and passes: MockMvc does not perform the ERROR dispatch at all, so it sees
the 405 that the live caller never gets. Same family as gotchas #16 and #119 — the things that are
only true in a container.

**What it costs today: nothing the PWA can reach.** It never sends a method an endpoint does not
have. A caller that did would follow the redirect and parse an HTML login page as JSON, which
reads as a server fault rather than as "wrong method" — so this is worth fixing before any third
party is told to call `/api/**` by hand.

**Why it was not fixed on the spot.** The fix is to let the error dispatch through — permitting
`/error` on the API and integration chains, or handling it outside security — and that changes the
final response of *every* failing request in the application. Immediately before a production
update, with five other findings in the same commit, that is the wrong trade. It is small and
self-contained once it is the only thing in a commit.

### What would make it urgent

- **Any third-party integrator calling `/integration/**` or `/api/**` by hand**, which is exactly
  who sends a wrong method.
- A PWA change that starts using a method an endpoint does not have — the symptom would be an
  unparseable response, not a 405.

## What would make this section urgent

- **18a and 18b both become live the moment the panel or 8081 is reachable from outside the
  plant network** — the same trigger §17 already carries.
- **18i's remaining measurement matters when a second writer arrives**: a second application node
  (§12), an importer pool above 1, or any audited write added to the mobile path.
- **18l and 18m each have their own trigger list**, above, because neither is a defect in the
  code as written: one is an invariant the installation has to hold, the other is arithmetic that
  changes when a ceiling does.
- **18j matters at a few thousand users**, which this plant is not.

What is left here is cheap to fix and none of it is urgent today, which is exactly why it is
written down rather than done in passing: 18b needs §17's answer first, and 18e needs a decision
about where a batch's failure boundary belongs. Neither should be settled by whoever happens to be
in the file next.

---

# 19. Running more than one instance — Kubernetes, or any N-node deployment

**Nothing here is built, and the application is deliberately single-instance today**
([deployment.md](deployment.md) says where that rule is kept). This section is the inventory for
the day somebody wants N replicas: what actually blocks it, what is already ready, and the
cheapest shape that works. Read out of the code on 2026-10-17 — §12 and §14 each name three
blockers in passing, and that list turned out to be neither complete nor still accurate.

The question here is **horizontal scale of one service**, which is a much smaller question than
splitting into several (§14, declined). Nothing below needs a rewrite; most of it is a volume and
a second Deployment.

## 19a. The import subsystem is destructive under N pods, not merely wasteful

This is the one to read first, because it is the only one that **loses data an operator produced**
and because neither §12 nor §14 mentions it.

`ImportJobRecoveryRunner` is an `ApplicationRunner`, so it runs on **every boot of every pod**,
and `ImportJobService.recoverStaleRunningJobs()` does two things:

```java
for (ImportJob job : importJobRepository.findByStatus(RUNNING)) {
    job.setStatus(FAILED);
    job.setErrorMessage("Import interrupted by server restart.");
    importJobRepository.save(job);
    fileStorageService.deleteQuietly(job.getFilePath());   // the uploaded file is gone
}
for (ImportJob job : importJobRepository.findByStatus(PENDING)) { /* re-queued */ }
```

With one instance this is exactly right: the only way a `RUNNING` row can exist at boot is that
the process died mid-import, so the job is dead and its file is rubbish. **With N pods the premise
is false.** A pod starting — a rolling update, a crash loop, a scale-up, anything Kubernetes does
routinely — marks the import that another pod is *actively running* as failed and **deletes the
uploaded file underneath it**. The administrator sees "Import interrupted by server restart" for
an import nobody restarted, and the file they uploaded is gone.

The `PENDING` half is milder but still wrong: every starting pod re-queues the same pending jobs,
and `ImportJobRunner.run` guards with `if (job.getStatus() != PENDING) return`, which is a read
rather than a claim — two pods can both see `PENDING` and both run the file.

**What it needs**: an owner. Either the single worker pod of §19f (which makes the premise true
again, because then there really is only one importer), or a claim — `UPDATE import_jobs SET
status = 'RUNNING', owner = :instanceId WHERE id = :id AND status = 'PENDING'` with the row count
as the decision, plus an instance identity so recovery only touches rows this instance owns.

## 19b. Three stores on the pod's local disk

```properties
app.attachments.storage-dir=./data/attachments
app.import.storage-path=./data/imports
app.template-guides.storage-dir=./data/template-guides
```

§12 sees the first one. All three break the same way: a file written by pod A does not exist for
pod B, while the row that names it is in a database both can see. A photograph uploaded through
one pod and requested through another is a 404 against a reading that says there is a photo; an
Excel file uploaded to one pod cannot be read by another pod's importer; a template guide is
downloadable from one third of the fleet.

The orphan sweep is worse than a 404, because it **acts** on a partial view: it walks the storage
root it can see and deletes files no row references. A pod that can see one third of the files
concludes the other two thirds are orphans. The 24-hour grace window
(`app.attachments.sweep.grace-hours`) buys time, not safety.

**What it needs**: one shared store before the second pod exists. A `ReadWriteMany` volume is the
smallest change and keeps every path in the code as it is; §12 is the other answer, with its costs
already weighed there.

## 19c. In-JVM state, where each pod has its own copy

| Where | What breaks with N pods |
|---|---|
| `SessionRegistryImpl` + `maximumSessions(1)` | "One browser per user" becomes per-JVM: with three pods a user holds three sessions and none expires the others |
| the panel's HTTP session (`server.servlet.session.persistent=false`) | without sticky routing, the next request lands on a pod that has never seen the session and redirects to `/login` |
| `LoginAttemptService` (a `ConcurrentHashMap`) | the five-failure lock becomes **5×N**: five pods, twenty-five guesses before a username locks — and §2 bounds nothing |
| `WebSessionMetadataStore` | `/web-sessions` lists the sessions of whichever pod answered |
| `AttachmentSweepService`, `AuditRetentionService` (progress in an `AtomicReference`) | a sweep started on one pod shows no progress to an administrator whose next request goes elsewhere |
| `ImportJobCancellationRegistry` | "cancel this import" reaches a pod that is not running it and does nothing |

**What it needs**: Spring Session (Redis or JDBC) for the panel — or sticky routing as a stopgap —
and a shared store for the throttle, or an explicit decision to accept 5×N. The two sweep
progress views are cosmetic and are solved for free by §19f.

## 19d. The six scheduled jobs, and the one that is already safe

The status is mixed now, which is why the "five unguarded scheduled jobs" of §12 is out of date:

| Job | With N pods |
|---|---|
| **log sheet generation** | **safe since 2026-10-17** — the cursor compare-and-set of §18l; the loser's rounds roll back and it logs one WARN |
| **expiry** | safe by construction: a conditional `UPDATE`, so N passes equal one |
| **import watchdog** | safe by construction: `failStaleRunningJobs(cutoff)` is a conditional `UPDATE`, and a live job's heartbeat keeps it out of the cutoff |
| audit purge, integration-usage purge, attachment sweep (three nightly crons) | **wasteful, not wrong**: idempotent deletes, run N times. The sweep also walks the whole store N times, which matters once the store is shared and large |
| `ImportJobRecoveryRunner` (startup, not `@Scheduled`) | **destructive** — see §19a |

So the job category costs one decision rather than six fixes.

## 19e. What is already ready, and is worth knowing before anybody estimates this

- **Mobile authentication needs no shared session state.** A JWT carries only *who*; the `jti` is
  checked against `api_sessions` and the roles are read from the database **on every request**, so
  any pod can serve any tablet with no stickiness and no replication. This is usually the biggest
  blocker in a migration like this, and it is already absent.
- **"One active mobile session per user" is enforced in the database**, by
  `ux_api_sessions_one_active` plus `pg_advisory_xact_lock` in `ApiSessionService` — a lock at the
  database, so it is already cluster-wide. (Its browser-side twin, in §19c, is not.)
- **The health probes Kubernetes wants already exist**: `/actuator/health/readiness` includes the
  database, `/actuator/health/liveness` does not, and both are public.
- **Static assets are content-hashed and served from the jar**, identical on every pod, with
  nothing to purge on a rollout.
- **Flyway is safe to start concurrently**: it locks its own history table, so one pod migrates
  and the others wait.

## 19f. The shape that removes most of the work

```
Deployment  api     replicas: N   — @Scheduled off, import executor off
Deployment  worker  replicas: 1   — the schedulers and the importer
```

Two properties that **do not exist yet** (`app.scheduler.enabled`, `app.import.worker.enabled`, or
a Spring profile doing the same) and the whole of §19a and §19d disappears: there is exactly one
importer again, so recovery's premise is true, and the crons run once. No `ShedLock`, no leader
election, no new infrastructure. If the worker ever has to scale too, *that* is when distributed
locking earns its keep.

## 19g. Configuration that has to change, none of it a blocker

- **Logs.** `app.log.path` writes rotating files to the pod's ephemeral disk. The `json-logs`
  profile already exists; turn it on and send everything to stdout.
- **`X-Forwarded-For` stops being optional.** Behind an Ingress, §18b is no longer a deferred
  finding: `server.forward-headers-strategy=framework` with the Ingress as the trusted proxy, and
  direct access to pods closed. It is the same decision §17 is waiting for, with the Ingress in
  nginx's place.
- **Connection pool arithmetic.** `maximum-pool-size` is **per pod**, and every audited write
  briefly takes a second connection (§18i). Five pods is fifty-plus connections against
  PostgreSQL's `max_connections`; either size it deliberately or put PgBouncer in front.
- **Rolling updates make the schema a contract.** With one instance the service stops and starts,
  so a migration can change anything. With N pods, old and new code are live together for the
  length of a rollout, which means expand-then-contract migrations — add the column, ship the
  code, drop the old column in a later release.

## 19h. The order, if it is ever wanted

1. **One shared store** for the three directories (§19b).
2. **The worker split** (§19f) — which also settles §19a and §19d.
3. Spring Session plus a decision about the login throttle (§19c).
4. `forward-headers-strategy` and closing direct pod access (§19g).
5. Connection-pool arithmetic (§19g).
6. JSON logs to stdout (§19g).

**Only 1 and 2 are mandatory**, and they are mandatory because without them the failures are
silent: a missing file behind a reading that says there is one, and an import killed by a pod
restart somewhere else. Steps 3 to 6 are configuration and arithmetic, and their failures
announce themselves.

## What would make it urgent

- **A second plant, or a second site served by one deployment** — the only requirement that
  actually argues for N instances rather than a larger one. At ~50 concurrent users and the
  measured headroom in [performance.md §2](performance.md), the current box is not the limit.
- **An availability requirement that survives a restart.** Today a restart is a full outage of a
  few seconds and nobody has asked for better; that ask is what buys the whole of §19.
- **A move to Kubernetes for operational reasons** — someone else running the platform, wanting
  one deployment model for everything — which is a legitimate reason that has nothing to do with
  load, and which makes §19a the first thing to fix rather than the last.

---

# 20. Per-asset parameters and warning/danger bands

*Raised as: "every pump may have its own ampere range — the band belongs to the asset, not to
the class."*

**Status: designed and being built.** This section is the reasoning and the measurements, kept
because the numbers are what chose the design and re-deriving them later would be expensive.

## What the model does today

| Fact | Where |
|---|---|
| A field definition belongs to **a class**, and nothing finer | `field_definitions.class_id`, [schema.md](schema.md) |
| Bands live in `field_definitions.validation` as `{"warning":{min,max},"danger":{min,max}}` | `FieldValidationSupport` |
| A sheet freezes the schema at generation into `log_sheets.field_definitions_snapshot` | [log-sheets.md](log-sheets.md) |
| Every resolution path keys on **`classId`** | `LogSheetFieldDefinitionsService`, `EntrySeverityEvaluator.definitionsFor` |
| The PWA keys on it too — `own.filter(fd => fd.classId === entry.classId)` | PWA `utils/sheetFieldDefinitions.ts` |
| `max_severity` is written **only** by `EntrySeverityEvaluator.apply`, server-side | `LogSheetService:468` (mobile merge), `:1127` (web fill) |

The last row is what makes a staged rollout safe: the client never sends severity and the server
never trusts a client-sent `classId`, so a PWA that does not yet understand per-asset bands
produces **correct stored data** and merely paints the wrong colour on the tablet.

## The measurements that chose the design

Taken from the operational database on 2026-10-21.

| Measure | Value |
|---|---|
| Assets | **10,900** — Steam Trap 6,671, Electric Motor 2,627, Pumps 1,555, others 47 |
| Classes carrying any field definition | **Pumps only** (11 fields) |
| Distinct `asset_name` among pumps | **507**, for 1,555 pumps |
| Names covering exactly one pump | **162** |
| Entries per generated sheet | **47**, on every one of 158 sheets |
| Distinct asset types within one sheet | **22** |
| Snapshot cost | 440 KB logical / 126 KB stored for 158 sheets → **~253 bytes per field definition** |
| Whole database | 53 MB |
| `server.compression` | **unset — Spring's default is off**, so the bundle goes to the tablet uncompressed |

All eight numeric pump fields also carry the same placeholder band, `-100..100`, with `danger`
identical to `warning` — the bands have never been real, which is why this was raised.

## Why "a private class per asset" was considered and rejected

It is seductive because it needs **no PWA change at all**: give the asset its own one-member
sub-class, point `asset_entries.class_id` at it, and every existing `classId` lookup — server
and client — keeps working untouched.

The measurements kill it:

| Design | Snapshot rows for one 47-entry sheet | JSON |
|---|---|---|
| Today (one class) | 11 | **2.9 KB** |
| A sub-class per asset *type* (22 per sheet) | 242 | 63 KB |
| A private class per *asset* (47) | 517 | **135 KB** |
| Per-asset **delta** (1–2 overridden fields each) | — | **~11 KB** |

The snapshot is stored on every sheet **and** sent in every bundle fetch, and compression is off,
so 135 KB crosses plant wifi per fetch per tablet. The 12× is bought entirely by duplication:
47 near-identical copies of the same 11 definitions differing in two numbers.

Two further costs, independent of bytes:

- **A class is a type.** 10,900 type rows describing 10,900 individuals is a modelling error, and
  it is the kind that spreads — every join, report and dropdown that means "kind of equipment"
  silently starts meaning "individual machine".
- Two dropdowns load the class table unpaginated — `AssetEntryWebController:67` and
  `LogSheetTemplateWebController:57` — and would render ten thousand `<option>` elements.

**And sub-classes do not collapse the problem either.** 507 distinct pump names for 1,555 pumps,
162 of them covering a single pump, with 22 distinct types inside each 47-entry sheet. Grouping
by type saves roughly 2× over per-asset, not the order of magnitude that would make it the
answer. Sub-classes remain worth having for the genuine groups (`WATER PUMP` ×21, `Corrosion
Inhibitor Injection Pumps` ×20, seven names with 12 each) — they are simply not the mechanism
for per-asset bands.

## The design

Three parts. The first needs no PWA change; the third does, and is backward compatible.

### a. One-level sub-classes

```sql
ALTER TABLE asset_classes ADD COLUMN parent_id BIGINT REFERENCES asset_classes(id);
```

A sub-class inherits its parent's fields and overrides them **by `field_key`**. One level only:
a class with a parent may not itself be a parent. An asset points at the leaf through the
existing `class_id`, so `entry.classId` is the sub-class and the snapshot's `classId` filter —
server and PWA — keeps working with no change.

Selecting a class in a template must reach its sub-classes:

```sql
WHERE a.class_id IN (SELECT id FROM asset_classes WHERE id = :classId OR parent_id = :classId)
```

Six queries in `AssetEntryRepository` carry the old equality. No denormalised `root_class_id` is
needed, because the depth is capped at one and the table is tiny.

### b. Per-asset overrides, on the asset

```sql
ALTER TABLE asset_entries ADD COLUMN field_source    VARCHAR(16) NOT NULL DEFAULT 'INHERIT';
ALTER TABLE asset_entries ADD COLUMN field_overrides JSONB;
```

| `field_source` | Fields come from | Bands come from | A new field on the class |
|---|---|---|---|
| `INHERIT` | class / sub-class | class / sub-class | reaches this asset |
| `RANGES_ONLY` | class / sub-class | `field_overrides` | **reaches this asset** |
| `FULL` | `field_overrides` | `field_overrides` | does **not** reach it |

`RANGES_ONLY` is the common case and the one that stays correct over time. `FULL` is a deliberate
detachment and must be visible as one in the UI, because the failure is silent: an engineer adds
a field to the class, forty pumps get it and three do not.

A JSONB column rather than a child table, because the overrides are always read as a whole for
one asset and never queried individually; the `AssetEntry` row is already loaded at generation,
so resolution costs no extra query, and deletion cascades for free.

### c. The snapshot carries a delta

```
log_sheets.field_definitions_snapshot   unchanged — class/sub-class definitions, once
log_sheets.field_overrides_snapshot     new — { assetId: { fieldKey: { warning, danger } } }
```

Resolution still happens **once, at generation**, so the "frozen at generation" guarantee that
makes historical data trustworthy is untouched.

The PWA merges the override onto the definition it already resolves. Five small changes:
`utils/sheetFieldDefinitions.ts` (the merge), `pages/LogSheetFillPage.tsx` (two call sites),
`utils/formDataValidation.ts`, `services/storage/restoreArchivedWork.ts`, and the `LogSheet`
type. A PWA that does not yet have them ignores an unknown key and shows class bands, while the
server keeps computing severity correctly — see the first table.

## The trap that would have shipped silently

`LogSheetGenerationService:121` captures the snapshot from **`template.getClassId()`**, while
`:299` writes **`asset.getClassId()`** onto the entry. The moment sub-classes exist those are
different values, the PWA's `fd.classId === entry.classId` filter matches nothing, and the
operator gets an **empty form** — no error, no log line. The `EXPLICIT` branch eight lines above
already does the right thing and says why in its comment; the `SCOPE` branch has to do the same.

## What was deliberately not done

- **No `root_class_id`.** One level makes the subquery cheap and avoids a cascade on every asset
  when a class's parent changes.
- **No private classes**, for the reasons above.
- **No override of `options`, `label`, `unit` or `data_type` in `RANGES_ONLY`.** Only the two
  bands. Anything else is a different field, and that is what `FULL` is for.

## An unrelated finding, worth taking anyway

`server.compression` is unset, so every mobile bundle is sent uncompressed. The payload is highly
repetitive JSON — the same column compresses 3.5× under TOAST on disk. Three lines in
`application.properties` shrink every bundle fetch, independently of this feature.

---

# 21. Editing a scoped template's asset list, which converts it to explicit

*Raised as: "after the scope has resolved its assets, let the admin add or remove one — and then
the template is no longer scope-based."*

**Status: designed and being built.**

## What the model does today

| Fact | Where |
|---|---|
| `log_sheet_templates.asset_selection_mode` is already `SCOPE` \| `EXPLICIT` | [schema.md](schema.md) |
| An `EXPLICIT` template's assets live in `log_sheet_template_assets`, composite PK `(template_id, asset_id)` | [schema.md](schema.md) |
| In `EXPLICIT` mode `scope_type`, `scope_id` and `class_id` are all null | README |
| A `SCOPE` template **re-resolves every run**, so new equipment joins the round by itself | [log-sheets.md](log-sheets.md) |
| The resolved preview already exists | `log-sheet-template-assets-preview.html`, `ScopedAssetPreviewRow` |
| `log_sheet_template_assets.asset_id` is `ON DELETE RESTRICT` | `MasterDataDeleteService.assertDeletableAssetEntry` |

So the destination state, its table, its mode flag and the preview screen all exist. What is
missing is the one-way door between them.

## The conversion

One transaction:

```
INSERT the edited list        → log_sheet_template_assets
asset_selection_mode          = EXPLICIT
scope_type = scope_id = class_id = NULL
```

## What it costs the operator, and why the UI must say so

**The template stops following the plant.** That is the entire difference between the two modes,
and it is invisible afterwards: a `SCOPE` template on "all pumps in Unit 4" picks up a pump
installed next month, and the converted one never will. The confirmation has to say this in
those words, not as "this will convert the template".

**Assets in the frozen list can no longer be hard-deleted** — `RESTRICT` on `asset_id`, reported
by `MasterDataDeleteService`. That is correct behaviour, but it is a new restriction appearing
on assets that had none, so the message has to name the template.

## The decision that was left open

**Converting back to `SCOPE` discards the hand-picked list**, and there is no way to tell which
removals were deliberate. The build offers conversion in one direction only; going back means
editing the scope fields, which is an existing screen, and the hand-picked rows are deleted at
that point with a confirmation of their own.

## Why this is not simply "edit the generated sheet instead"

A generated sheet's entries are already frozen and already editable through the custom-sheet
path. This is about the **template**: the admin wants the next round, and every round after it,
to carry the corrected list. Editing one sheet fixes one day.

---

# 22. Forms that are not a log sheet — sheet-level questions, scheduled patrols, and ad-hoc reports

*Raised as three separate asks that turn out to be one: "a log sheet needs a few questions of
its own, not per asset"; "security should get a scheduled round to go and look at something";
and "HSE needs to file a violation whenever they see one, with a form builder."*

**Status: designed, nothing built.** Two of the three need almost no new model, and saying which
is most of the value here.

## The fact that reorganises all three

**`asset_classes` + `field_definitions` is already a form builder.** [schema.md](schema.md) says
so in as many words — *"This is the form schema, and it is data, not code"*. Field type, unit,
required, order, select options, warning and danger bands, and per-option severity are all
defined through the admin panel without a deployment, and since V5 they resolve through a
three-layer chain with per-asset overrides.

So none of these three asks needs a form builder. What two of them need is a **carrier** for a
form that is not "one asset inside one round", and the third needs nothing at all.

---

## a. A question that belongs to the round, not to an asset

*"Were all the pipes checked?"* — asked once per sheet, not once per pump.

### What the model does today

| Fact | Where |
|---|---|
| A field definition belongs to an **asset class**, and an answer to an **entry** | `field_definitions.class_id`, `log_sheet_entries.form_data` |
| A sheet freezes its schema at generation | `log_sheets.field_definitions_snapshot` |
| `log_sheets.notes` is the only sheet-level text, and it is **free text, web only** | [log-sheets.md](log-sheets.md) |

### The shape, which is the one already proven here

Mirror the existing three parts, keyed to the **template** rather than to a class:

| | Column |
|---|---|
| The questions | a new `log_sheet_template_fields`, the columns of `field_definitions` with `template_id` in place of `class_id` |
| Frozen at generation | `log_sheets.sheet_field_definitions_snapshot` JSONB |
| The answers | `log_sheets.sheet_form_data` JSONB |
| Optionally, severity | `sheet_max_severity` / `sheet_breached_fields` |

`FieldValidationSupport`, `EntrySeverityEvaluator` and the PWA's `DynamicClassForm` then work
untouched — only their input comes from somewhere else.

### Two shapes rejected

**A synthetic "sheet asset".** Tempting because it needs no new table: give every sheet one
extra entry whose class holds the round-level questions. It breaks
`ux_asset_entries_active_sub_function`, appears in NFC matching, appears in the silent-asset
report, and adds one to the N-of-M counter. Every report that counts assets would count a
fabricated one, which is the category of defect [performance.md](performance.md) and
[reports.md](reports.md) exist to prevent.

**Reusing `notes`.** Free text: no validation, no required-ness, no reporting. It cannot answer
"in what fraction of rounds was this yes", which is the reason the question is being asked.

### Decisions to take before building

1. **A required sheet-level question must block completion**, or it is decoration.
2. **Where in the operator's flow.** It belongs at the **end**, before submit — "were all the
   pipes checked" is only answerable once they have been.
3. **Custom (template-less) sheets**: do they get these? If yes the definitions attach to the
   sheet, not the template.
4. Whether sheet-level severity feeds the exceptions report.

### The rollout constraint, which is the part that bites

The bundle key is additive and an older PWA ignores it — **and then never answers a required
question, so the server refuses the completion and the operator is stuck in the field.**
Ship the client first, or gate required-ness behind a date. This is not hypothetical: the
per-asset band feature was safe to ship in either order precisely because nothing about it was
required, and this one is not that.

---

## b. A scheduled patrol for security

**This is a log sheet. It probably needs nothing new at all.**

| What security wants | What already does it |
|---|---|
| Every night at 02:00, go and look | a scheduled template |
| Only the security team sees it | `operational_unit_id` + assignment |
| Scan at each point | the NFC tag, which **belongs to the position, not the equipment** |
| A few questions per point | an asset class and its fields |
| Offline, with photographs | the bundle and the attachment subsystem |
| Chase what was not done | the existing reports |

The point worth knowing: `AssetNfcSupport.effectiveNfcTag` falls back to the **sub-function's**
tag when the asset carries none. So "north gate" or "extinguisher, corridor 3" is registered as
a position with a tag, and scanning works without anything being modelled as equipment.

The only gap is a question attached to no point — *"was the visitor log complete?"* — and that
is **(a)** above.

So: **(a) plus registering the patrol points, and the scheduled case is finished.** Try this
before building anything in (c); it may be the whole requirement.

---

## c. An ad-hoc report — a violation, a hazard, an observation

Here something new is genuinely needed, but it is **one table, not an engine**.

### Why it cannot be a log sheet

A log sheet is scheduled, scoped, assigned and asset-bound. An observation is none of those:
nobody scheduled it, nobody was assigned it, it may concern no asset, and the observer starts
it. Forcing it in breaks generation, the claim/assign lifecycle, the not-null `asset_id` on an
entry, and the silent-asset report.

### One definition, two carriers

```
field definition  (owner made polymorphic)
        │
        ├── scheduled carrier →  log_sheets         (exists)
        └── ad-hoc carrier    →  form_submissions   (new)
```

Make `field_definitions` polymorphic in its owner — `owner_type` + `owner_id` instead of
`class_id` — with three owners: `ASSET_CLASS` (today), `TEMPLATE` (a above), `FORM`. One editor,
one validator, one renderer, three uses.

**The cost is real and should be said plainly:** `class_id` is `NOT NULL` and a lot of code
assumes it is populated. The cheaper alternative is a sibling `form_fields` table with the same
columns and two editors — and two editors of the same thing drift. The PWA's `merge` and `added`
paths were exactly that, and a threshold key reached one and not the other until it was found by
a user. Prefer the polymorphic owner; budget the migration honestly.

### `form_submissions`

Take the columns from `nfc_fault_reports`, which is already this shape and has worked:

```
client_action_id UNIQUE   idempotency — a retry from a tablet makes no duplicate
local_id                  the device's own id
source                    WEB / MOBILE
status                    NEW → TRIAGED → CLOSED
reported_by_user_id
operational_unit_id       for routing, NOT for ownership — see below
created_at / synced_at    device time vs server time
form_data        JSONB    the answers
form_snapshot    JSONB    the schema they were answered against — mandatory, see below
asset_id         NULL     when it concerns a machine
sub_function_id  NULL     ...or a position
```

### The three things that actually block it

**1. Attachments, which is the largest piece.**

```sql
attachments.log_sheet_id  BIGINT NOT NULL
attachments.asset_id      BIGINT NOT NULL
attachments.field_key     VARCHAR NOT NULL
```

A photograph of a violation has no log sheet and may have no asset. Both columns have to become
nullable behind an `owner_type`/`owner_id`, which also touches the orphan sweep, the access
checks and the storage-key scheme. This is more work than the submissions table itself. A first
release without attachments is possible and close to useless — nobody acts on a hazard report
with no picture.

**2. The access rule is unlike every other one in this system.** Everything else is "the user
sees these units". But **the reporter need not belong to the responsible unit** — anyone can see
a hazard and must be able to file it. `visibleUnitIds()` does not answer this. Two rules are
needed: *what I filed* and *my unit's queue*. Decide it before the first query is written;
changing it later means rewriting all of them.

**3. The PWA needs a new record type, not a new sync engine.** The outbox, Dexie, session and
conflict handling all exist. What is new is a UI flow that starts outside a round — a "file an
observation" action reachable from anywhere, rather than from inside a sheet.

### Two rules the form builder inherits, both learned the hard way

**Every submission freezes its own schema.** `form_snapshot` is not optional. Without it,
editing a form rewrites history — the exact reason `field_definitions_snapshot` exists.

**A field key is immutable once anything has been submitted against it.** A form builder invites
a user to invent a key and then regret it. Renaming one is a seven-column data migration
([AGENTS.md](../AGENTS.md) #157): when it was done through the panel alone, **1,201 readings
were orphaned in silence** and V6 was written to recover them. For a new builder, either lock
the key after first use or ship the full rewrite with it. The third option — allow it for now —
is what produced that migration.

---

## Suggested order

1. **(a) sheet-level questions.** Lowest risk, and it completes (b).
2. **Register the patrol points as tagged sub-functions.** No code; testable today.
3. **If 1 and 2 cover security, stop here.** They may well cover all of it, which would leave
   (c) needed only for HSE — a far clearer scope than "forms for every department".
4. Only then: polymorphic field owner → `form_submissions` → polymorphic attachments → PWA.

Step 3 is the one to take seriously. §14 of this file declined splitting the system into
services for the same reason: an abstraction built before the second real user of it is a
guess, and this one has a cheap way to find out.

---

# 23. Disabling a field instead of deleting it — **built**

*Raised as: "a field of a class, a sub-class or an asset should be switchable off and on rather
than removed — it should still render on sheets that already carry it, and still appear in the
reports wherever a value was recorded for it."*

**Status: built.** Kept here because the reasoning behind two of the decisions is the kind that
gets re-opened: why the unique index stays non-partial, and why a hard delete still exists.

The reference documentation is [schema.md](schema.md) (`field_definitions.deleted`),
[security.md](security.md) (the V7 permission) and [reports.md §7](reports.md) (what a report
does with a retired parameter). §5 covers what is still *not* built in the same area.

## The shape it took

Half of it already existed, which was most of the point. `field_definitions.deleted` had been
in the schema since V1, eight backend readers and the PWA had always honoured it, and **nothing
had ever written it** — so removing a parameter from the panel was a real `DELETE`. Wiring up
the write half was the whole feature.

| Layer | How it is switched off |
|---|---|
| Class | `POST /asset-classes/{classId}/fields/{fieldId}/state?disabled=true` sets `deleted` |
| Sub-class | the same endpoint — a sub-class *is* a class. Switching its own row off means "stop overriding", so the parent's row is inherited again; the page lists both so that is visible |
| Asset | already existed: `"removed": true` in `field_overrides` (`FULL` only). What changed is that the row's bands and definition are now stored **beside** the flag, so unticking it restores the asset's own configuration instead of silently adopting the class's |

And the behaviour that was asked for:

| Where | A disabled parameter |
|---|---|
| A new sheet | not offered — it is not captured into the snapshot the sheet freezes |
| A sheet raised while it was on | still renders and still re-validates. No code needed: the snapshot already froze it |
| A sheet open on a tablet right now | unaffected, same reason — an operator mid-round does not lose a field under them |
| The PWA | **no change at all**. A disabled parameter never reaches a bundle, and the client already filtered on `deleted` for the rows it stores |
| The per-asset report and chart | shown wherever a value was recorded, marked «غیرفعال» |
| Severity already stored on an entry | untouched — `max_severity` was computed at submit and is not recomputed |

## The index must stay as it is

```sql
CREATE UNIQUE INDEX ux_field_definitions_class_key_lower
    ON field_definitions (class_id, lower(field_key));   -- NOT partial
```

With a soft delete the key stays taken, and **that is correct — do not add `WHERE NOT
deleted`.** A partial index would allow two definitions of one key, one disabled and one new,
and a stored reading would then belong to neither in particular. The key would stop being an
identity and become a label, which is exactly the failure [AGENTS.md](../AGENTS.md) #157 and
migration V6 were written to recover from.

The practical consequence: after disabling, a parameter with that key cannot be *created*
again — it must be **re-enabled**. That is the behaviour to want.

## Why a real delete still exists

A typo created and removed in the same minute does not deserve a tombstone. The hard delete is
kept for a parameter **nothing refers to**, and refused otherwise —
`FieldDefinitionRepository.isFieldKeyReferenced`, over `log_sheet_entries.form_data`,
`log_sheet_entry_revisions.form_data` and `asset_entries.field_overrides`, scoped to the class
*and its sub-classes* (a parent's parameter is inherited, so its readings carry the sub-class's
id — asking about the parent alone answers "unreferenced" for a parameter with a thousand
readings).

A sheet's frozen `field_definitions_snapshot` deliberately does **not** count. That sheet
renders from its own copy and never reads the row, so deleting it cannot affect the sheet — and
counting it would block the one case the hard delete exists for.

## What it cost, measured against what was predicted

| Predicted | Actual |
|---|---|
| Migration: none | **V7 — one permission row.** The column needed nothing; the *endpoint* needed a permission, because in this codebase an endpoint with no row in `permissions` cannot be granted or audited |
| Backend: `deleteField` becomes disable-or-delete, an enable endpoint, the report stops filtering | as predicted, plus `AssetFieldResolver.resolveIncludingDisabled` — the report needed a *resolution*, not just an unfiltered read, because a parameter can be switched off at three different layers |
| Class field page: status column, action, greyed rows | as predicted, plus the sub-class page listing what its parent switched off — otherwise an inherited parameter goes silently missing from an inheritance that is supposed to be automatic |
| PWA: none | **none**, confirmed by test rather than by inspection |

## The payoff that was predicted, and is now available

Re-create `Audio` and `Video` on the Pumps class **as disabled parameters**. The three orphaned
attachment readings on the operational database reattach to a definition immediately — matching
is by key — leave the orphan list, and are never offered on a new round. No migration, no data
touched.
