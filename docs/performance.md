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

| Page | SQL statements | Median | Notes |
|---|---|---|---|
| `/asset-entries` | **3** | 140 ms | 87 assets. The pattern that works — batch label maps |
| `/operational-units` | **10** | 125 ms | 104 units. Was ~212 before the fix in §3 |
| `/users` | 28 | 61 ms | 11 users; 14 `roles` + 12 `user_roles` |
| `/log-sheets` | 99 → **100** | 218 ms | 96 of them on `locations` — scope labels. The «پیشرفت» column added **one** statement for the whole page, not one per row — see below |
| `/nfc-fault-reports` | ~6 † | **79 ms** | 42 reports. Was an **unbounded** read of the whole table — see §3c |
| `/asset-status-requests` | ~30 † | 151 ms | 25 rows; one `isLatestForAsset` per row — see §4b |
| `/locations` | **152** | **503 ms** | 147 of them on `locations` — parent labels |
| `/` (dashboard) | — | 18 ms | |

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

# 4. Open: the parent-label N+1 on the other list pages

**Known, measured, and deliberately not fixed.** This is the reference to come back to.

## What it is

`ReferenceLabelService.operationalUnitLabel(id)`, `locationLabel(id)` and their siblings each do
one `findById`. The list templates call them per row — often twice, once for the mobile layout
and once for the desktop one:

```html
<div class="d-md-none small text-muted" th:text="'والد: ' + ${@labels.parentLabelForLocation(loc.parentId)}"></div>
<td class="d-none d-md-table-cell" th:text="${@labels.parentLabelForLocation(loc.parentId)}"></td>
```

Hibernate's first-level cache dedupes repeats **within one request**, so the real cost is one
query per *distinct* parent on the page, not per row. That is why `/locations` shows 147 and not
500: 180 locations, many distinct parents.

## Why it is left alone for now

- **It is read-only.** No write path, no transaction, no risk to data.
- **The default page size is 25.** 250 is opt-in, and `/locations` at 25 rows is roughly a fifth
  of the 503 ms above.
- **The fix is not local.** It means changing the label helper's contract — from "give me a label
  for this id" to "give me a map for these ids" — across roughly six controllers and their
  templates. That is a real change with real rendering-regression surface, and it buys latency on
  pages nobody has complained about.

## What would make it urgent

- The locations registry grows past a few hundred rows and `/locations` is used daily.
- Somebody raises the default page size above 25.
- A page like this is put in front of a tablet rather than a desktop on the LAN.

## How to fix it when the time comes

The pattern already exists in this codebase — copy `/asset-entries`, which renders 87 rows in
**3 statements**. Load the labels the page needs as one map before rendering:

```java
model.addAttribute("locationLabels", referenceLabelService.locationLabels());   // one query
```

…and have the template read the map rather than call a per-id helper. `ReferenceLabelService`
already exposes `locationLabels()`, `operationalUnitLabels()`, `subFunctionLabels()` and the rest
for exactly this; they are simply not used by these pages.

Do it one page at a time, `/locations` first, and check the rendered output against the current
one — the helpers apply fallbacks (`pick(name, code, id)`) that a naive map lookup would lose.

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

One related read is worth knowing about: `visibleAssetIds()` materialises up to **5000** asset ids
for a unit-scoped user and passes them as an `IN (…)` list on every page load. That is a ceiling,
not a page size, and an installation past it would silently scope the queue to the first 5000
assets. Unrestricted admins skip it entirely (`unitIds == null`).

---

# 5. Standing limits recorded elsewhere

| Subject | Where |
|---|---|
| Per-tick cost of `/api/log-sheets/inbox`, and the three options for reducing it | [roadmap.md §3](roadmap.md) |
| Why API authorities are resolved per request and not cached | [README — Mobile API sessions](../README.md#mobile-api-sessions-stateful-jwt) |
| Audit write volume and the retention purge | [jobs.md](jobs.md) |
| Asset-count ceiling per log sheet, and why the scheduler only warns | [AGENTS.md](../AGENTS.md) gotcha #89 |
| PWA install payload, and the two changes that halve it | PWA `docs/deployment.md` |
