# Performance — what was measured, and what is deliberately left

Everything here is **measured against the running application**, not estimated. Each number says
how it was obtained so it can be reproduced, and each open item says what would make it urgent.

> Numbers were taken on a development machine that was also running PostgreSQL, a browser and
> Maven. Treat them as an upper bound for a dedicated server, and as directly comparable to each
> other — which is what they are for.

---

# 1. How to measure

## SQL statements per request

The panel's list pages are where query counts go wrong, and Hibernate will tell you exactly what
it ran:

```bash
java -jar target/backend-offline-first-0.0.1-SNAPSHOT.jar \
     --logging.level.org.hibernate.SQL=DEBUG
```

Load the page **twice** — the first load warms the connection pool, the statement cache and any
label lookups — then count the `select` lines the second load added to `ProdLog/app.log`. Group
them by table; an N+1 shows up as one table with a count that tracks the row count.

**Turn it off afterwards.** At DEBUG this logger writes a line per statement, which on a bulk
import is tens of thousands of lines.

## Table scans per request, without touching the log level

`loggers` is not an exposed actuator endpoint here, so Hibernate's SQL log cannot be turned on for
a running instance — it needs a restart. Postgres will answer the same question without one:

```sql
SELECT relname, idx_scan + seq_scan FROM pg_stat_user_tables;
```

Snapshot that either side of one page load and the difference is scans-per-table for that page,
which is exactly the shape an N+1 makes: one table whose count tracks the number of distinct rows
rendered. Load each page **twice** and measure only the second, so connection-pool warm-up and
first-touch caching stay out of the number.

**The trap: these statistics are not flushed synchronously.** A snapshot taken immediately after a
request usually does not include that request yet, and the scans turn up in the *next* page's
window instead. The first run of this measurement reported every page's cost against the page
after it — `/locations` at 0 and `/plant-systems` at 264, when the truth was 147 and 164. Sleep a
few seconds between the request and the snapshot. The check that it worked: three pages
(`/locations`, `/operational-units`, `/asset-entries`) have counts recorded in this file from the
Hibernate log, and a correctly settled run reproduces them exactly.

## Request latency

Log in once, keep the session cookie, request the same path a dozen times and take the median.
Any HTTP client will do; the point is to hold the cookie so you are timing the page and not the
login.

## Database round trips

`pg_stat_database.xact_commit` before and after a burst of N requests gives transactions per
request. It is coarser than the SQL log — Open Session In View wraps a request in one transaction
— but it needs no restart and no log level change.

---

# 2. Measured: the panel's list pages

At `?size=250`, the largest page size the toolbar offers. **The default is 25**, so divide by
roughly ten for what an ordinary user sees.

| Page | Table scans | Rows | Notes |
|---|---|---|---|
| `/asset-entries` | **3** | 87 | The pattern the others were made to copy — batch label maps |
| `/main-functions` | 250 → **11** | 250 of 1143 | Was the worst page in the panel; see §4 |
| `/plant-systems` | 165 → **2** | 164 | Two label columns, two different formats |
| `/locations` | 152 → **6** | 181 | 147 of the old count were `locations` — parent labels |
| `/log-sheets` | ~120 → **5** | 115 | 115 of the old count were `locations` — scope labels |
| `/sub-functions` | 88 → **6** | 87 | Four levels of parent fallback |
| `/users` | 50 → **16** | 12 | Roles per row **and** one last-administrator check per row |
| `/operational-units` | 10 → **5** | 4 | Supervisors/operators were already batched (§3); the parent column was not |
| `/my-inbox` | **3** | — | Two lists sharing one scope map |
| `/log-sheet-templates` | **7** | 2 | |
| `/nfc-fault-reports` | **4** | 42 | Was an **unbounded** read of the whole table — see §3c |
| `/asset-status-requests` | ~24 † | 12 | One `isLatestForAsset` per row — still open, see §4b |
| `/roles` | **2** | 6 | |
| `/asset-classes` | **2** | 2 | |

The «before» figures are the ones this file carried until the fix in §4; where an older row quoted
*SQL statements* from a Hibernate log and the new one counts *table scans* from `pg_stat_user_tables`,
the two are close but not identical — a statement that touches two tables is one of the former and
two of the latter. Compare within a column, not across.

† Latency measured as §1 describes (ten requests on a held session, median). The two counts
marked † are **derived from the code**, not counted in a SQL log: one search + its `count(*)`,
plus the batch lookups the row needs. Count them properly before quoting them as measurements.

## Measured: the API

`GET /api/log-sheets/inbox`, authenticated, after the JWT change that resolves authorities from
the database on every request:

| Concurrency | Throughput | Median | p95 |
|---|---|---|---|
| 1 | 25 req/s | 38 ms | 52 ms |
| 4 | 106 req/s | 34 ms | 66 ms |
| 16 | **200 req/s** | 73 ms | 123 ms |

The fleet produces roughly **7 req/s at peak** — fifty tablets, a 30-second sync timer, a handful
of calls each — so there is about thirty times the headroom needed. See the README's
[Mobile API sessions](../README.md#mobile-api-sessions-stateful-jwt) for why that resolution is
not cached.

---

# 3. Fixed: two queries per row on the operational-units page

The list called `getSupervisorIds(unitId)` and `getOperatorIds(unitId)` **once per row** — two
queries per unit on top of the page itself. Fifty on a default page of 25; five hundred at 250.

Nothing looked slow at four units, which is exactly how this kind of thing survives.

`OperationalUnitService.supervisorIdsByUnit` / `operatorIdsByUnit` now take the whole page's ids
and return a map, in one query each:

```java
return unitSupervisorRepository.findByUnitIdIn(unitIds).stream()
        .collect(Collectors.groupingBy(UnitSupervisor::getUnitId,
                Collectors.mapping(UnitSupervisor::getUserId, Collectors.toList())));
```

Measured after, with **104 units on the page**:

```
GET /operational-units?size=250   →  10 SQL statements
      7  operational_units
      1  users
      1  unit_supervisors    ← was 104
      1  unit_operators      ← was 104
```

A unit with nobody assigned is **absent from the map**, not mapped to an empty list; callers read
it with `getOrDefault(id, List.of())`. `OperationalUnitBulkDeleteIntegrationTest` pins that
contract, along with agreement with the per-unit methods and the fact that assignments do not
bleed between units.

---

# 3b. The progress column, and how it was kept to one query

«N از M دارایی» on `/log-sheets` and «کارتابل من» is a count over `log_sheet_entries` per sheet —
exactly the shape that produced the operational-units N+1 above. `LogSheetProgressViewService`
takes the whole page's ids and returns a map in one grouped query:

```sql
SELECT log_sheet_id, COUNT(*), SUM(CASE WHEN max_severity IS NOT NULL THEN 1 ELSE 0 END)
FROM log_sheet_entries WHERE log_sheet_id IN (:ids) GROUP BY log_sheet_id
```

At `size=250` that is one statement rather than 250. `idx_log_sheet_entries_filled` (partial,
`WHERE max_severity IS NOT NULL`) serves the filled count; the existing
`idx_log_sheet_entries_log_sheet_id` serves the total. A sheet with no entries is **absent** from
the map rather than mapped to zero, and callers read it with
`getOrDefault(id, LogSheetProgressSummary.EMPTY)` — the same contract
`OperationalUnitService.supervisorIdsByUnit` uses.

**The mobile progress endpoint is deliberately not on the inbox's cost curve.** `POST
/api/log-sheets/progress` is called only when a tablet has something new to report — the device
sends the entries carrying a `locallyEditedAt` marker and nothing else, and skips the request
entirely when there are none. So its cost is proportional to readings actually taken, not to
`tablets × sheets ÷ interval` the way `GET /api/log-sheets/inbox` is (§ roadmap.md 3). One row per
changed entry, one conditional UPDATE per sheet.

---

# 3c. Fixed: the fault-report queue read the whole table

`GET /nfc-fault-reports` called `NfcFaultReportService.findVisible()`, which returned **every
report ever filed** and handed the lot to the template. One row is written per broken or missing
chip and nothing ever deletes them, so the page grew with the plant's entire NFC history — and it
is read most on exactly the days that history is longest. There was no filter either: a reviewer
hunting for one machine had to fall back on the browser's own find.

`NfcFaultReportRepository.search` now does scope, status, free text and paging in **one** query,
ordered `created_at DESC, id DESC` and backed by `idx_nfc_fault_reports_created_at` on the same
two columns. The tie-break is not cosmetic: `created_at` is the reporting clock and repeats freely
— a phone syncing a backlog files several reports in the same millisecond — and without it those
rows can swap between pages and be shown twice or not at all.

The page is now bounded by the page size instead of by the table: 79 ms median for a default page
of 25, and the same work whatever the table grows to.

# 3d. Fixed: 301 KB of one detail page was repeated HTML comments

Thymeleaf copies `<!-- … -->` into the response verbatim, so an explanatory comment inside a
`th:each` is emitted **once per iteration**. Measured on a real 23-asset sheet:

| | Before | After |
|---|---|---|
| `/log-sheets/{id}` response | 639 KB | **340 KB** |
| …of which HTML comments | **301 KB** (47%) | 2.8 KB |
| Worst single comment | 329 copies, 247 KB | 1 copy, absent from the output |

The explanations were not deleted — they are the reason the markup is maintainable. They are
written as **parser-level** comments, `<!--/* … */-->`, which Thymeleaf strips when it parses the
template. The text stays exactly where the next reader needs it and never reaches the browser.

This is bandwidth and parse time on the page a supervisor opens most, on tablets over plant
wifi; the server-side cost was never the issue.

`LogSheetDetailParametersIntegrationTest.noCommentIsCopiedIntoTheResponseOncePerRow` fails the
build if any comment appears more than three times in one rendered page. The threshold is loose on
purpose: it is not a byte budget, it is a tripwire for the one mistake — a paragraph inside a loop
— which is invisible in review and only ever shows up as a page that is mysteriously large.
Verified by putting the 329-copy comment back: the test failed and named it.

---

# 4. Fixed: the parent-label N+1 on the list pages

Recorded here as "known, measured and deliberately not fixed" until it was fixed. The conditions
this section named as the ones that would make it urgent had all quietly arrived: `main_functions`
had grown to **1143 rows**, and a second measurement pass found two pages worse than the
`/locations` this section was written about, neither of which had ever been measured.

## What it was

`ReferenceLabelService.locationLabel(id)` and its siblings each do one `findById`, and the list
templates called them per row:

```html
<td th:text="${@labels.parentLabelForLocation(loc.parentId)}"></td>
```

Hibernate's first-level cache dedupes repeats **within one request**, so the cost was one query
per *distinct* parent on the page, not per row — which is why `/locations` showed 147 rather than
181.

`/main-functions` was the worst, and its number was hiding: 250 scans for a page size of 250, over
a table of 1143 rows. The page size was the only thing bounding it. Raising it — a toolbar
control, one click — would have taken the page to 1143 queries in a single request.

## What was done

Every affected page now builds its labels once in the controller and the template reads a map:

```java
model.addAttribute("parentLabels", referenceLabelService.parentLabelsForMainFunctions(result.getContent()));
```
```html
<td th:text="${parentLabels[mf.id]}"></td>
```

Measured after, at `?size=250`: `/main-functions` 250 → **11**, `/plant-systems` 165 → **2**,
`/locations` 152 → **6**, `/log-sheets` ~120 → **5**, `/sub-functions` 88 → **6**,
`/users` 50 → **16**, `/operational-units` 10 → **5**. Each is now a fixed number of queries per
page rather than a number that grows with the data.

Nothing about the schema changed, so no migration was needed and **V4 stayed closed**.

## Three decisions inside it worth knowing

**The maps are keyed by the row's own id, not by the parent's.** A row id is never null; a parent
id frequently is, and SpEL throws on a null map index rather than yielding null. Keying by the row
keeps the "no parent" decision in Java, where it is tested, instead of in a Thymeleaf expression
where the first root-level row on any page would have brought the page down.

**Two label formats live in this service and they are not interchangeable.** For one and the same
location, `parentLabelForLocation` renders «نیروگاه» — name, else code, else id — while
`parentLabelForSystem` renders «مکان: LOC-01 - نیروگاه», a Persian type prefix over `code - name`.
The old version of this section warned about exactly this («the helpers apply fallbacks that a
naive map lookup would lose»). The answer is `ReferenceLabelBatchEquivalenceTest`, which runs both
the batch builder and the per-row helper against one fake database and asserts they agree row by
row, over every branch: parent present, parent absent, dangling parent, name-only, code-only, and
the precedence order on main and sub functions. Three deliberate mutations were checked against it
— wrong format, inverted precedence, dangling parent rendered as «—» — and each was caught.

**The per-row helpers were kept.** They are dead as production code and are not dead: they are the
oracle the equivalence test compares against, and a detail page wants one label and not a map.

## What `/users` needed beyond a label map

Two per-row questions, not one. The roles column asked `getRoleIdsForUser` per user — and built
labels for **every user in the system** to render one page of them. Separately,
`isLastActiveAdministrator` ran per row to decide whether to draw a delete button, at two queries
each.

Both are now page-wide: `RoleService.roleIdsByUserId(ids)` and
`UserService.lastActiveAdministratorIds(ids)`. The second one matters beyond latency, so it is
worth being explicit: **it decides what is drawn, never what is allowed.** `UserService.delete`
and `setActive` still call the per-row `isLastActiveAdministrator` themselves and are untouched.
`UserListBatchLabelsIntegrationTest` asserts the two agree for every user in the database, so the
page cannot drift into offering a button the server will refuse.

---

# 4b. Open: one `isLatestForAsset` per row on the status-request queue

`/asset-status-requests` is paged and filtered in SQL — the free-text search added alongside it
matches the asset's code and name and the requester's name through subqueries, in the same
statement. What it still does is call
`AssetStatusRequestService.isLatestForAsset(id)` **once per row**, to decide whether the
«واگردانی» control is offered — 25 lookups on a default page, 250 at `?size=250`.

Left alone, for the same reason as §4: each is a single-row indexed lookup
(`findFirstByAssetIdOrderByIdDesc`), the count is bounded by the page size rather than by the
table, and 151 ms is not what anybody is waiting on. The fix when it matters is one query — the
newest request id per asset for the ids on the page, `GROUP BY asset_id` — the same batch-map
shape §3 used for the units page.

**What would make it urgent:** somebody raising the default page size, or the undo rule growing
into something that needs more than one row per asset to decide.

# 4d. Fixed: a 5000-asset ceiling decided who could see a status request

`visibleAssetIds()` materialised the caller's reportable asset ids — `PageRequest.of(0, 5000)` —
and passed them to the query as an `IN (…)` list on every page load. **That is a ceiling, not a
page size**, and on the installation this was found in — 200,000 assets — it was firing:
requests for the assets beyond the cut were absent from the queue on every page, with no error
and nothing on screen to suggest a row was missing. Which 5000 survived was undefined too, since
the underlying query carries no `ORDER BY`.

**Pagination could never have helped**, and the distinction is the useful part of this entry:
paging bounds work done *per row*; it does nothing about a set computed *whole, before the first
row is fetched*, and then used as a filter. §4b above is the first kind and is genuinely bounded
by page size. This was the second kind.

Raising it was not an option either: 200,000 bind parameters exceeds PostgreSQL's limit of
65,535, so the query would not run at all.

The scope now resolves in the statement, as an `EXISTS` semi-join over the same
`REPORTABLE_ASSETS_CTE` that `AssetAccessService.findReportableAssets` uses — one definition of
"reportable", which is what keeps *what the page lists* and *what the user may act on* the same
rule. Native, because that CTE is recursive and JPQL cannot express it; the unrestricted path
keeps its original JPQL untouched, and `AssetStatusRequestScopeIntegrationTest` holds the two
against each other so they cannot drift.

Per page load this also removes one query (the id materialisation) and 5000 bind parameters.

## Measured

`EXPLAIN (ANALYZE, BUFFERS)` against the development database — 10,900 assets, 49 requests, a
supervisor of one unit — for the paged query the page actually issues:

| | |
|---|---|
| Execution | **3.2 ms** |
| Planning | 25 ms cold, and it is planned once per statement shape |
| Buffers | 372 shared hits, none read from disk |
| Rows | 25, the page size |

**What to watch, and it is visible in that plan:** the CTE's first branch joins
`asset_entries.sub_function_id` and PostgreSQL chooses a **`Seq Scan on asset_entries`** for it.
The one index that could serve the join is `ux_asset_entries_active_sub_function`, and it is
**partial** (`WHERE active`) while the CTE does not filter on `active` — PostgreSQL only uses a
partial index when the query's predicate implies the index's, so it cannot be used.

It costs nothing on the measurement above only because that unit's `location_units` is empty, so
the join's other side is empty and the scan stops immediately. **On a site where locations are
attached to units and the table holds 200,000 rows, this node is the one that will decide the
page.** Take the plan there before choosing between adding `AND a.active` to the CTE — a domain
decision about what "reportable" means, not a mechanical one — and a plain index on
`sub_function_id`.

That empty `location_units` is itself worth chasing: it is [security.md
F6](security.md), and it means the unit's whole reportable set is arriving through the log-sheet
branch of the union rather than through location ownership.

The same shape is worth grepping for anywhere else: a list materialised in Java and handed to a
query as a filter. At the time of writing this was the only one — `PageRequest.of(0, <large>)`
appears once in the codebase, and the NFC fault-report queue, which looks similar, passes
`unitIds` straight into its query and never had the problem.

---

# 4c. Measured: what the list-page JavaScript costs in the browser

Everything above this point counts SQL. `enterprise-ui.js` decorates each list table after render —
marking the primary column, tagging empty («—») cells and badge cells, wiring the density control —
and that work is per cell, so it was worth measuring rather than assuming. On `/main-functions` at
`?size=250` (250 rows, **2000 cells**), in the browser:

| What | Cost | Notes |
|---|---|---|
| Per-cell decoration | **3.8 ms** | The regex per cell and the `.badge` subtree query per cell are both cheap; this was the part that looked expensive and is not |
| `domInteractive` | 841 ms | Whole page, 375 KB, including network |
| One density switch | **~150 ms** | Forced layout over 2000 cells |

The density switch is the only figure worth a second look, and it is acceptable as it stands: it is
a one-off on an explicit user action, not a per-frame or per-page cost. What makes it more than the
class change alone is that `height` and `padding` are in the transition list for table cells, so the
table re-lays-out on each frame of the 140 ms animation instead of once. Dropping those two and
keeping `box-shadow` would make it a single layout; nobody has asked for it, and at the default page
size of 25 the cost is a tenth of the figure above.

# 4e. Fixed: first-paint UI restyling

## Cause

The original list-page problem had two layers. Late page/table wrappers caused large layout
changes. Moving only those wrappers to Thymeleaf reduced that shift, but left visible status,
button and column changes behind. Per-cell classes are not merely cosmetic: primary and
technical cells change font size; operation cells change button geometry. The old note that
these changes were invisible was incorrect.

`enterprise-ui.js` also guessed badge state from Persian text after DOMContentLoaded and
again through a MutationObserver. Besides repainting badges, its success regex matched
«ناموفق» through the substring «موفق». Arbitrary role names and selected-item labels could be
interpreted as lifecycle states. This was display logic, not a server lifecycle problem.

## Current implementation

- Templates supply final header/action/filter, card, column, operation and empty-state classes.
  The late column/empty-state rebuild and badge observer have been removed. Empty rows keep
  their original message in a lightweight CSS layout instead of being rebuilt after paint.
- `status-badge` opts into the status appearance. The existing Bootstrap class chosen by the
  server or polling code determines its palette. Approved sheets retain their distinct darker
  green. The batch-import initial markup and refreshed rows use the same class contract.
- Table badge sizing is a CSS descendant rule, not a class added to the containing cell later.
- Long values are capped by `max-width` on data cells rather than by a script that measured
  each cell's text length. The 44-character threshold it used was a proxy for "this cell is
  wide", which is what `max-width` answers directly: a cell under the cap is unaffected by it.
  Cells containing a control are excluded so a button is never clipped instead of text.
- `ui-preferences.js` is a small local, content-versioned, parser-blocking head script. An
  external script without async/defer in this position runs before body parsing; it does not
  need an inline script or a hidden page. It restores sidebar collapse and emits constrained
  CSS for each stored table key, covering density and hidden columns independently. Invalid
  JSON/types are ignored; key and column validation prevent CSS injection through storage.
- Pre-paint rules stop matching when that table's controls have applied their state. This
  preserves subsequent density/column interaction without a race or a global-density guess.
  Table keys are stable in source order (or the existing id), so conditional table rendering
  no longer shifts the identity used for newly saved preferences.
- Existing toolbar slots reserve 52px on desktop and 88px on narrow layouts. Existing CSS
  row-count caps still handle long tables before scripts run. Font preloads now cover local
  weights 400, 500 and 700. Content hashes and the one-year static-resource cache are unchanged.

## Verification and limitations

`UiFirstPaintTemplateTest` composes the actual layout and log-sheet template with all eight
statuses and an empty-list case, without starting Spring Boot, jobs or PostgreSQL. Set
`-Dui.preview=true` to write those actual rendered pages and local assets to `target/ui-preview`
for browser QA; these are synthetic data fixtures, not a running production environment.

The rendered list was compared before enhancement (only the head preference reader allowed)
and after enhancement in the in-app browser. Status colour/font/box and operation-button box
were unchanged; desktop compact-density geometry matched exactly after toolbar reservation.
The two fixtures measured identically at 1440px — page height **885px → 885px**, row height
**51px → 51px**, cell font **12.32px** either way, toolbar box **52px** either way. The only
difference is that the toolbar has no children before the scripts run and two after, which is
the point: its box is reserved by the template, so filling it displaces nothing.
Mobile and empty-list cases, saved density, hidden columns and sidebar interaction are also
part of the manual checks. This is not a measured global cold-cache CLS guarantee: font arrival,
network latency and every data-dependent widget are not simulated by these fixtures.

Automated guards: `FirstPaintNeedsNoScriptTest`, `UiFirstPaintTemplateTest`,
`UiAssetsStayLocalTest`, `StaticAssetsAreVersionedTest`, `CssHasNoSystemFontsTest`, and
`node --test src/test/js/ui-preferences.test.cjs`. Java tests run with Maven's `-o` switch.
No new package, server endpoint, permission, migration or business-state transition is involved.

---

# 5. Standing limits recorded elsewhere

| Subject | Where |
|---|---|
| Per-tick cost of `/api/log-sheets/inbox`, and the three options for reducing it | [roadmap.md §3](roadmap.md) |
| Why the status-request queue's scope is resolved in SQL rather than materialised | §4d above, and [security.md F7](security.md) |
| Why API authorities are resolved per request and not cached | [README — Mobile API sessions](../README.md#mobile-api-sessions-stateful-jwt) |
| Audit write volume and the retention purge | [jobs.md](jobs.md) |
| Asset-count ceiling per log sheet, and why the scheduler only warns | [AGENTS.md](../AGENTS.md) gotcha #89 |
| PWA install payload, and the two changes that halve it | PWA `docs/deployment.md` |
