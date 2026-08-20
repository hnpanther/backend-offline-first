# Roadmap — designs that are not built yet

> **Nothing in this file is implemented.** Every other document under `docs/` describes the
> system as it is; this one describes work that has been thought through but not done. Keep the
> separation — a reader who cannot tell the two apart will trust a design as if it were
> behaviour, which is exactly the failure the documentation rule in `CLAUDE.md` exists to
> prevent.
>
> When one of these is built, move the content into the reference documents it belongs to
> (`hierarchy.md`, `security.md`, `log-sheets.md`, `schema.md`) and delete the section here.

Each section records the **facts about the current system** that the design depends on. Those
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
