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
| `/log-sheets` | 99 | 218 ms | 96 of them on `locations` — scope labels |
| `/locations` | **152** | **503 ms** | 147 of them on `locations` — parent labels |
| `/` (dashboard) | — | 18 ms | |

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

# 5. Standing limits recorded elsewhere

| Subject | Where |
|---|---|
| Per-tick cost of `/api/log-sheets/inbox`, and the three options for reducing it | [roadmap.md §3](roadmap.md) |
| Why API authorities are resolved per request and not cached | [README — Mobile API sessions](../README.md#mobile-api-sessions-stateful-jwt) |
| Audit write volume and the retention purge | [jobs.md](jobs.md) |
| Asset-count ceiling per log sheet, and why the scheduler only warns | [AGENTS.md](../AGENTS.md) gotcha #89 |
| PWA install payload, and the two changes that halve it | PWA `docs/deployment.md` |
