<!--
  Moved here from README.md. The README keeps a short pointer instead of a copy —
  two versions of the same formulas would disagree within a month, and a report
  whose documented formula is wrong is worse than one with no documentation.
-->

# Reports

Eight report pages under `/reports/*`. This chapter is the reference for **what each number
means and exactly how it is calculated** — the formulas are not obvious from the screens, and
two of them (compliance, self-serve) have denominators that are easy to assume wrongly.

## Who can see them, and how much

| | |
|---|---|
| Permission | **`GET:/reports`** — a single authority covering all eight pages |
| Granted by default to | `ADMIN`, `HIGH_USER`, `SUPERVISOR` |
| Not granted to | `OPERATOR`, `SENIOR_OPERATOR` — they do not even see the sidebar section |
| Mutating endpoints | none; every report is read-only |

Once inside, **how much** a user sees is a separate rule from **whether** they get in:

| Viewer | Scope |
|--------|-------|
| `ADMIN` / `HIGH_USER` | Unrestricted — the whole plant (`visibleUnitIds()` returns `null`) |
| `SUPERVISOR` | Their supervised units **plus every descendant unit**, plus any unit they personally operate |

That is the same `AssetAccessService.visibleUnitIds()` used for log sheets, so a supervisor of
unit 1 sees units 2 and 3 in every report automatically. Because the permission is a database
row, an administrator can revoke it from `SUPERVISOR` on the roles page without a code change.

> **No per-report granularity yet.** Anyone with `GET:/reports` gets all eight. To restrict a
> single page (say, keep *Workforce* admin-only) you would add a dedicated permission row and
> a matching `@PreAuthorize`.

## The counting window

Every page has a "بازه (روز)" selector. Two different rules apply, deliberately:

| Report | Window applies to | Why |
|--------|-------------------|-----|
| Compliance, Workforce, Overview | `log_sheets.created_at` — when the sheet was **raised** | A sheet belongs to the period it was *owed* in. Window on completion instead and a backlog cleared today inflates today while hollowing out the month the work was actually due. |
| Exceptions, Data quality | `COALESCE(completed_at, submitted_at)` — when the reading was **taken** | These are about readings, and a reading's date is when it was recorded. |

The selector is clamped to 1–365 days server-side, so a hand-edited query string cannot turn a
report into a full-table scan.

---

## 1. داشبورد مدیریتی — `/reports/overview`

Six KPI cards plus a 12-month trend.

| Card | Formula |
|------|---------|
| نرخ تحقق به‌موقع | `onTime / (submitted + expired)` |
| قرائت در محدوده خطر | count of entries with `max_severity = 'DANGER'` (warning count shown beneath) |
| باز و گذشته از مهلت | open sheets (`PENDING`/`ASSIGNED`/`IN_PROGRESS`) whose `due_at < now`. **Not** windowed — it is a live figure |
| خرابی NFC باز | sum of unresolved `nfc_fault_reports` per asset |
| ابطال‌شده | `VOIDED` in window (expired shown beneath) |
| ثبت دستی | `PWA_MANUAL entries / all submitted entries` |

The trend table is 12 calendar months ending with the current one; each row's bar is
`onTime / submitted` for that month alone.

## 2. نرخ تحقق و تأخیر — `/reports/compliance`

Grouped by operational unit and, separately, by template.

| Column | Meaning |
|--------|---------|
| کل | every sheet raised in the window, whatever its state |
| ارسال‌شده | `status = SUBMITTED` |
| به‌موقع | submitted **and** `due_at IS NOT NULL` **and** completion `<= due_at` |
| با تأخیر | submitted **and** had a deadline **and** completion `> due_at` |
| منقضی / لغو / ابطال | `EXPIRED` / `CANCELLED` / `VOIDED` |
| باز | still `PENDING` / `ASSIGNED` / `IN_PROGRESS` |
| **نرخ تحقق** | **`onTime / (submitted + expired + cancelled)`** |

Three things worth knowing about that rate:

- **Open work is excluded from the denominator.** It has not had its chance yet; counting it
  would punish a unit for work that is not due.
- **Cancelled work *is* in the denominator.** Cancelling is a failure to perform. Leave it out
  and a unit could cancel everything it could not finish and score 100%.
- **A sheet with no `due_at` is neither on-time nor late.** It cannot be judged against a
  deadline it never had, so it is absent from both columns (though still counted in کل).

**میانه تأخیر / صدک ۹۰** are computed over the *overshoot only* — sheets finished early are
dropped rather than entered as negative lateness, which would let punctual work cancel out
overdue work and hide the problem. Percentile = nearest-rank on the sorted samples.

## 3. تخطی از بازه مجاز — `/reports/exceptions`

Every submitted reading that breached its warning or danger range, danger first, newest first.

Reads the stored `log_sheet_entries.max_severity` / `breached_fields`, which are computed **at
write time** against the ranges captured in that sheet's `field_definitions_snapshot` — the
thresholds in force when the reading was taken, so re-tuning a range never rewrites history.
One row per offending field, so an entry breaching two parameters appears twice.

**Filters and paging.** The page takes `days`, `unitId`, `dangerOnly`, `page` and `size`
(25 / 50 / 100 / 200, default 50):

| Control | Behaviour |
|---------|-----------|
| واحد عملیاتی | Restricts to one unit. The chosen unit is **intersected** with the viewer's visible units, never substituted for them — picking a unit you cannot see returns nothing rather than widening your scope |
| فقط موارد خطر | DANGER only; otherwise WARNING and DANGER |
| تعداد در صفحه | Page size; changing it returns to page 1 |
| Pager | First / previous / current / next / last, hidden entirely when there is only one page |

Any filter change resets to the first page — a hidden `page=0` in the filter form — because
keeping the old page number after narrowing the result usually lands on an empty page.

**Paging is on the log-sheet _entry_, not on the displayed lines.** One entry breaching two
parameters renders as two lines, so a page can show slightly more lines than its size; the total
above the table counts entries, which is what the pager steps through. Paging the expanded lines
instead would mean fetching everything just to know where page 2 starts, which is exactly the
cost the pager exists to avoid. `countBreachedEntries(...)` supplies the total from the same
indexed predicate as the page query.

## 4. کیفیت داده — `/reports/data-quality`

Three sections, three different questions about whether the data can be trusted.

| Section | Formula / rule |
|---------|----------------|
| نسبت ثبت دستی | `PWA_MANUAL / entries that carry a reading` per unit. A null `entry_source` on a filled entry predates the field and counts as **scanned**, not manual — treating unknown as manual would invent a problem out of old rows. Bar turns amber ≥10%, red ≥30% |
| سلامت تگ‌های NFC | assets with `status = OPEN` fault reports, **oldest first** — it is a maintenance queue, not a leaderboard. Marking a report بررسی‌شده removes it from this queue |
| دارایی‌های بدون قرائت | **active** assets with no reading since the window start; "هرگز" means none has ever been recorded, and those sort first. Uses the **reporting** scope, so an asset reached only through a log sheet still counts as yours to watch |

> **What counts as a reading.** A sheet is raised with one entry per asset and submitted whether
> or not every asset was reached, so "appeared on a submitted sheet" is *not* the same as "was
> read". Both sections above key on `max_severity IS NOT NULL`, which is exactly "form data is
> non-empty" — `EntrySeverityEvaluator` nulls that column when the data is empty and always
> writes at least `OK` when it is not.
>
> This was wrong in both places and the errors pointed opposite ways. The manual rate divided by
> every entry, so a unit with 94 entries of which 3 held a reading showed an all-manual round as
> 2% instead of 67%. Silent-asset detection counted an untouched entry as a reading, so it
> reported **zero** silent assets on a plant where 46 active assets had never been read — hiding
> precisely the equipment the section exists to surface. `DataQualityReportIntegrationTest` pins
> both directions.

## 5. نیروی انسانی و بار کاری — `/reports/workforce`

| Column | Formula |
|--------|---------|
| تکمیل‌شده | submitted sheets where `completed_by_user_id = this user` |
| با تأخیر / نرخ تأخیر | of those, how many missed `due_at`; rate = `late / submitted` |
| میانگین زمان رسیدگی | `avg(completion − COALESCE(claimed_at, assigned_at, created_at))` |
| به ازای هر اپراتور | `totalSheets / distinct operators assigned to the unit` |
| پیک‌آپ / انتساب | sheets with a `claimed_at` / with an `assigned_at` |
| **خودگردانی** | **`claimed / (claimed + assigned)`** — measured against *routed* work only; sheets nobody has picked up yet say nothing about how work reaches people |

> Intended as a coaching and load-balancing tool. A low number can mean heavier work, not worse
> performance — the report cannot tell the difference.

## 6. دلایل اقدامات — `/reports/actions`

Every EXTEND / CANCEL / VOID / UNVOID / ADMIN_REOPEN that carries a written explanation, newest
first. Filters on `comment IS NOT NULL` rather than on action type, so it stays correct if
another action is given a reason later. **This is the only place in the system where the *why*
behind a deadline change or an invalidation is recorded.**

## 7. پارامترهای دارایی — `/reports/asset-parameters`

Per-asset reading history and trend chart. Its asset picker uses the **reporting scope**
(ownership *or* responsibility through a log sheet), not the registry scope — see
[hierarchy.md](hierarchy.md#5-access-scope--the-part-that-must-be-right).

## 8. تاریخچه دارایی — `/reports/asset-history`

One asset's timeline, merging two kinds of event that are stored separately and must stay
separate:

| Event kind | Source table | Means |
|---|---|---|
| `STATUS` | `asset_status_history` | The operational state moved (`IN_SERVICE` → `OFF`, …) |
| `ACTIVATION` | `asset_activation_history` | The asset was registered, installed or removed |

**Why they are merged for display but not in storage:** switching a pump off for maintenance and
physically removing it from the plant are different facts. One column could not answer both.
The page presents one chronological list because that is how a person reads a history; the
tables stay apart because that is how the facts stay answerable.

Each status row carries its provenance — which log sheet, which entry, which field, and which
approved request produced it — so «چه کسی، چه زمانی، از چه راهی» is answerable for every change.

| Parameter | Meaning |
|---|---|
| `assetId` | The asset. May also be resolved from `q` (exact code or name). |
| `q` | Search text for the picker |
| `statusLimit` | Newest-N status events, clamped server-side to 1–1000, default 200 |

`statusTruncated` is set only when the cap actually bit, so the page does not say "showing the
latest 200" over a three-row timeline.

**Scope is the reporting scope**, the same as asset-parameters: a supervisor of the unit
responsible for a sheet the asset appeared on may read its history, even if the asset's location
belongs to another unit. A supervisor of an unrelated unit is refused — and refused *properly*:
no asset in the model and zero events, not a filtered list that leaks counts.

**A status of literally `OFF` must render.** Thymeleaf evaluates the strings `"off"`, `"false"`
and `"no"` as boolean false, so `th:if="${status}"` silently hides exactly those values — and
«خارج از سرویس / OFF» is the status an operator most needs to see. Use `#strings.isEmpty`.
Found on a live page; no unit test caught it.

Entry points: the asset registry, the log sheet detail page, and the actions column of the
asset status request queue.

---

## Report performance at scale

Measured on a synthetic year at the stated target load — **10 log sheets/day × 50 assets ×
365 days = 3,650 sheets and 164,250 entries** — on the same PostgreSQL the app uses:

| Query | Time | Plan |
|-------|------|------|
| Compliance by unit | **0.8 ms** | seq scan of `log_sheets` (3.6k rows — trivial) |
| Operator throughput | **0.4 ms** | seq scan of `log_sheets` |
| Out-of-range exceptions | **7 ms** | **index scan** on `idx_log_sheet_entries_breaches` |
| Breach counts (overview) | **12 ms** | index scan + aggregate |
| Manual-vs-scanned split | **77 ms** | parallel seq scan of `log_sheet_entries` |
| Last reading per asset | **64 ms** | seq scan of `log_sheet_entries` |

**Verdict: yes, comfortably.** The whole overview page is well under a second at one year.

How each family behaves as data grows:

- **Anything reading `log_sheets` stays cheap indefinitely.** That table grows ~3,650 rows/year;
  even a decade is ~36k rows, which Postgres scans in milliseconds.
- **The exception report does not degrade**, because `idx_log_sheet_entries_breaches` is a
  *partial* index covering only WARNING/DANGER rows. Breaches are a small minority, so the index
  stays small no matter how large the table becomes. This is the one report an external system
  might poll frequently, and it is the one built to be polled.
- **The two full-scan aggregates grow linearly** with entry count: ~70 ms at 1 year, so roughly
  0.35 s at 5 years and 0.7 s at 10. Acceptable for a page someone opens; the point to revisit
  them is when they cross ~1 s, and the fix then is a nightly rollup table rather than more
  indexes (they aggregate over *all* rows in the window, so no index can avoid the read).

Two things that were deliberately built to avoid trouble:

- The overview's breach figures are **counted in SQL**, not by fetching rows and counting them
  in Java. Counting fetched rows silently stopped growing once a period exceeded the page cap.
- Open NFC fault reports are **filtered in SQL**. Loading the whole table and filtering in Java
  is fine with a handful of rows and linear in the whole table forever after.

One known limit, stated rather than hidden: **دارایی‌های بدون قرائت** examines a bounded slice of
assets (4× the display limit) rather than the entire registry. With a few hundred assets that
is every asset; with tens of thousands it is a sample. Revisit alongside the rollup table above.


---

## Adding a report

1. Add the method to `ReportWebController` under `@PreAuthorize("hasAuthority('GET:/reports')")`.
2. Resolve scope with `AssetAccessService` — and write **both** variants: a scoped query and an
   unrestricted one. `visibleUnitIds()` returns `null` for an admin, and a CTE binding
   `:unitIds` to null matches nothing, which silently shows an administrator an empty report.
3. Aggregate **in SQL**. Fetching rows to count them in Java stops being correct the moment a
   page cap is involved, and stops being fast immediately.
4. Decide which window rule applies — raised (`created_at`) or read
   (`COALESCE(completed_at, submitted_at)`) — and say so in the table above.
5. Clamp any user-supplied limit server-side.
6. Test with a **scoped** user. An admin passes almost any scope bug, because their scope is
   everything.
7. Document the formula here, in the same commit.

## Related

- **[schema.md](schema.md)** — the tables and the partial indexes these queries rely on
- **[hierarchy.md](hierarchy.md)** — registry scope vs reporting scope, and why reports use the latter
- **[log-sheets.md](log-sheets.md)** — where the measured data comes from
- **[security.md](security.md)** — the scope rules that decide which rows a report may count
