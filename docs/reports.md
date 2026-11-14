<!--
  Moved here from README.md. The README keeps a short pointer instead of a copy —
  two versions of the same formulas would disagree within a month, and a report
  whose documented formula is wrong is worse than one with no documentation.
-->

# Reports

Eight report pages under `/reports/*`, **plus the landing dashboard at `/`** — which is not one
of the eight, has its own authority and its own window rule, and is documented in §0 below. This
chapter is the reference for **what each number means and exactly how it is calculated** — the
formulas are not obvious from the screens, and two of them (compliance, self-serve) have
denominators that are easy to assume wrongly.

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
| **Landing dashboard (`/`)** | `log_sheets.created_at` — **for every number on the page, readings included** | §0 explains why its breach counts do *not* follow the row above. |

The selector is clamped to 1–365 days server-side, so a hand-edited query string cannot turn a
report into a full-table scan.

**These windows are closed at the top (`<= to`) and the dashboard's is half-open (`< to`).** The
eight report pages take `days` back from now with no upper bound at all — in practice `to` is
null, so the question "does `to` include its own millisecond" never arises. The dashboard names
both ends, so it has to answer it, and the answer is the one `LogSheetListFilter` already gives
the log-sheet list: half-open, so «یک روز» is exactly one day and one period's `to` is the next
one's `from` with no overlap and no gap.

---

## 0. داشبورد مدیریتی (صفحه اول) — `/`

The page every login lands on. **Not** one of the eight: its own authority, its own window
shape, and a different question — not "how are we doing over the last N days" but "what happened
to the rounds raised in this day / week / month, unit by unit".

| | |
|---|---|
| Permission | **`GET:/`** — category `general`, the single permission that column holds |
| Granted by default to | `ADMIN`, `HIGH_USER`, `SUPERVISOR` — **V16** derives the grant from whoever holds `GET:/reports` |
| Not granted to | `OPERATOR`, `SENIOR_OPERATOR` — they land on «کارتابل من» instead |
| Scope | the same `AssetAccessService.visibleUnitIds()` as everything else |
| Mutating endpoints | none |

> **It used to be an inventory counter** — seven plant-wide `count(*)` calls (locations, asset
> classes, assets, sub-functions, log sheets, users, units) with no scope in any of them, which
> is precisely why it could only ever be an administrator's page: those totals describe a plant,
> not anybody's work. The counts themselves have not moved; `/reports` still shows the asset
> inventory and the log-sheet totals.

### The window

`period` ∈ `DAY` / `WEEK` / `MONTH` → 1 / 7 / 30 days, **half-open**, back from the **next
Tehran midnight**:

```
to   = next 00:00 Asia/Tehran   (exclusive)
from = to − period days         (inclusive)
```

Three decisions worth knowing, each with a failure behind it:

- **The anchor is plant midnight, not «now».** Measuring back from the request instant makes
  every reload a different window, so two people comparing the same screen compare different
  numbers. The cost is that `to` sits a few hours in the future, where nothing is counted yet.
- **The zone is `Asia/Tehran`, passed in by the controller**, never read from the host. This is
  the defect the twelve-month trend shipped with: on a UTC host every boundary moved 3½ hours
  earlier, so a round raised at 02:00 Tehran on the first of a month counted in the previous
  one. `PlantZoneIsPinnedTest` and `DashboardPeriodTest` both guard it now.
- **A week is seven days and a month is thirty** — *not* «since the start of the current Jalali
  week / month». A calendar month would need Jalali→Gregorian conversion, which this codebase
  does not have, and a window that resets on the first of the month shows a nearly-empty
  dashboard on that day — the one day a manager is most likely to open it.

**An unrecognised `period` is refused, not defaulted.** `DashboardPeriod.fromNullableOrDefault`
answers `null` for anything it does not know and the controller throws; the reader gets a Persian
flash message and a redirect to `/` with no parameter. Coercing would answer 200 with a window
nobody asked for, which is how somebody reads the wrong month and acts on it — the same rule
`LogSheetDateField` and `IntegrationLogSheetQuery` record for their own parameters. Absent or
blank means `WEEK`.

### The column is `created_at`, and it is not configurable

Every number on the page describes **one set of rounds**: those whose `created_at` falls in the
window, classified by the status they are in *now*. That is what makes a row add up —

```
raised = completed + open + expired + cancelled + voided
```

— with no round counted twice and none missing. The page no longer explains this in prose (the
explanation was hidden, then the summary sentence under the title removed, both on request); the
rule is unchanged and documented here.

The dashboard therefore does **not** take a `dateField` the way the log-sheet list does. Under
`completed_at` the column «چند ساخته شده» would mean "rounds raised at any time that happen to
have been finished in this window", which is a different question wearing the same heading, and
the row would stop adding up because an open round has no completion date at all.

### The per-unit table

One row per operational unit **that raised at least one round in the window**, busiest first.

| Column | Formula |
|---|---|
| واحد عملیاتی | `operational_units` «code — name», via one batched label lookup. `null` renders «بدون واحد مسئول» |
| ساخته‌شده | `count(*)` — every round raised in the window, whatever became of it |
| تکمیل‌شده | `status IN (SUBMITTED, APPROVED)` — through `COMPLETED_STATUSES`, never `SUBMITTED` alone |
| تأییدشده | `status = APPROVED` |
| در انتظار تأیید | **`تکمیل‌شده − تأییدشده`**, derived rather than counted — a second SQL counter for `SUBMITTED` alone would be free to disagree with the first the day a third completed status appears |
| باز | `status IN (PENDING, ASSIGNED, IN_PROGRESS)` |
| عقب‌افتاده | of باز, those with `due_at IS NOT NULL AND due_at < now`. **A live figure inside a historical window** — the one number that changes between two reloads with no new work. A round with no deadline cannot be late; a delivered one is not open |

> **عقب‌افتاده is usually zero, and that is the expiry scheduler working.** `LogSheetScheduler`
> runs every 60 s by default (`app.scheduler.log-sheet-expiry-ms`) and moves an overdue open
> round to `EXPIRED`, so the window in which a round is both open and past its deadline is at
> most one tick wide. Verified live: setting an open round's `due_at` into the past and loading
> the page immediately showed 1, and the next load showed 0 with منقضی up by one. So the column
> reads **«a deadline passed in the last minute» — or «the expiry job is not running»**, which is
> the second reading and the more valuable one: a persistently non-zero figure on the landing
> page means the scheduler is wedged or the service was down, and `/reports/overview`'s «باز و
> گذشته از مهلت» card has exactly the same property.
| منقضی / لغو‌شده / ابطال‌شده | `EXPIRED` / `CANCELLED` / `VOIDED` |
| هشدار / خطر | readings on this unit's **completed** rounds in the window whose stored `max_severity` is `WARNING` / `DANGER` |
| **نرخ تکمیل** | **`تکمیل‌شده ÷ ساخته‌شده`** |

**نرخ تکمیل is not the compliance rate.** `/reports/compliance` computes
`onTime / (submitted + expired + cancelled)` and answers a question about *deadlines*, excluding
open work because it has not had its chance yet. This one is `completed / generated` and
**includes** open work in the denominator, because it answers a question about a *period*: of
everything raised in this window, how much is done. A census that excluded work in progress
would read 100% on a day whose rounds have barely started. Two different numbers, two different
names, deliberately.

**جمع کل** is the sum of the rows on screen, computed in Java from rows already fetched. The KPI
cards read the same object, so a card and the table's last line cannot disagree.

### The page, top to bottom

A manager's overview: numbers, each a door into the page that explains it — never a list that
belongs on another page.

| Part | What it shows |
|---|---|
| Header | title, the window **as dates** (`rangeFrom` – `rangeTo`), and the period as a three-way segmented control — **امروز / ۷ روز / ۳۰ روز**, plain links with `aria-current="true"` on the active one, no form and no script |
| Welcome card | the viewer's name and username. For a **plant-wide** viewer (`CAP:SCOPE_PLANT_WIDE`) it also carries the plant at a glance — **قالب‌های لاگ‌شیت فعال** (templates whose `active` is not false — the generator's own reading, so a null counts) · کلاس‌ها · واحدهای عملیاتی — as one line of plain counts: whole-plant `count(*)`s, **not period-bound** and not scoped, which is exactly why a unit-scoped viewer is shown two plain badges instead of numbers that are not about their units (`DashboardService.plantStructureOrNull`) |
| آلارم‌ها | **only for holders of `GET:/alarms`.** Three counts — **تعیین تکلیف نشده** (live, unacknowledged), **در حال پیگیری** (acknowledged, still live), **باز در محدوده خطر** (live, either state, severity `DANGER`) — each a link to `/alarms` filtered to exactly what it counts (`?state=OPEN`, `?state=ACKNOWLEDGED`, `?state=LIVE&severity=DANGER`). Scoped exactly as the queue is, so the number and the length of the list it opens agree. **Not bound to the period**: an alarm raised last month and never looked at is the one this exists for. A zero is shown calm (`is-quiet`), not in the accent colour. Three indexed `count(*)` queries; see [alarms.md](alarms.md) §6 |
| شاخص‌های کلیدی این بازه | six tiles, all read from the totals line: ساخته‌شده · نرخ تکمیل (x از y) · **در انتظار تأیید** (تأییدشده beneath) · باز و گذشته از مهلت (کل باز beneath) · **منقضی‌شده** (لغو‌شده beneath) · قرائت در محدوده خطر (هشدار beneath) |
| به تفکیک واحد عملیاتی | the per-unit table above, with the drill-down to the log-sheet list for the window; only figures that need a look are coloured |
| دسترسی سریع | shortcuts, each behind its own permission |

The template is rendered by `DashboardTemplateTest` with a bare Thymeleaf engine, so everything it
shows — the dates, the alarm counts — arrives from `DashboardController` already computed (AGENTS.md
#185). Every small text on it meets WCAG AA contrast against white; the tiles keep the project's own
Vazirmatn and Bootstrap Icons, served locally — the panel loads nothing from the internet.

### Why the readings follow the round's window, not their own

This is the one place the dashboard departs from the table at the top of this chapter, and it is
a decision rather than an oversight.

`/reports/exceptions` windows a reading on `COALESCE(completed_at, submitted_at)` — when it was
recorded — because there the *reading* is the subject. On the dashboard the **round** is the
subject: every other number in a unit's row counts rounds raised in the window. Counting
readings by their own clock would put a row's breach figure and its completion figure on
different populations — and a reader divides one by the other. Two numbers printed side by side
that describe different sets are worse than either alone.

So `countBreachesByUnitAndSeverityForSheetsCreatedBetween` joins the entry to its sheet and
windows the **sheet's** `created_at`. Consequences, both intended:

- A round raised yesterday and completed today contributes its breaches to **yesterday's**
  dashboard, where its completion is also counted.
- The dashboard's خطر figure and `/reports/overview`'s «قرائت در محدوده خطر» are therefore
  **not** the same number for the same number of days, and neither is wrong.

Severity is read from the stored `log_sheet_entries.max_severity`, never evaluated here — it is
computed at write time against the thresholds frozen into that sheet's
`field_definitions_snapshot`, so re-tuning a class never rewrites history. The predicate matches
`idx_log_sheet_entries_breaches`, the partial index covering only the WARNING/DANGER rows. An
`OK` reading is not a breach, and a breach on a round nobody delivered is not counted at all.

### Units that raised nothing

They get **no row** — a plant with hundreds of units would bury the ones that did work — but the
page states how many there were: «N واحد از M واحد در دسترس شما در این بازه هیچ لاگ‌شیتی
نداشته‌اند». The denominator is the viewer's scope: `unitIds.size()` for a scoped viewer (free),
`count(*)` over `operational_units` for a plant-wide one (one query).

Reported rather than omitted on purpose. «Nothing was raised here» is exactly what a periodic
review exists to surface, and a table that merely lacked the row would say it only to a reader
who already knew which units should be present — the mistake the data-quality report made in the
other direction when it reported **zero** silent assets on a plant where 46 had never been read.

### A round that belongs to no unit

`log_sheets.operational_unit_id` is nullable, and such a round is invisible to every scoped
viewer: a scoped query filters `operational_unit_id IN (:unitIds)`, and `NULL IN (…)` is never
true. A plant-wide viewer gets a «بدون واحد مسئول» row, and the page says underneath that nobody
else can see it — because they are the only person who can act on it.

### The queries

Three for a scoped viewer, four for a plant-wide one, **independent of the number of units**:

| Query | What it is |
|---|---|
| `LogSheetRepository.lifecycleCensusByUnitCreatedBetween` | the eight counters, `GROUP BY operational_unit_id` |
| `LogSheetEntryRepository.countBreachesByUnitAndSeverityForSheetsCreatedBetween` | `GROUP BY operational_unit_id, max_severity` |
| `ReferenceLabelService.operationalUnitLabelsFor` | labels for the ids on the page, not every unit in the plant |
| `OperationalUnitRepository.count()` | plant-wide viewers only — the denominator above |

A query per unit would be the most obvious N+1 this system could have, on the one page every
login opens. See [performance.md](performance.md) §2 for the measurement.

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

**The months are Tehran months.** `ReportWebController` passes `DateUtils.TEHRAN` into
`monthlyTrend`, and that constant is now the only place in the application that names a zone. It
is worth stating because the argument used to be `ZoneId.systemDefault()`, and on a UTC host —
the default for a container and for most Linux installs — that started every bucket at 03:30
Tehran instead of 00:00. Two consequences, both silent: a round raised at 02:00 Tehran on the
first of a month was counted in the **previous** month, and for the first three and a half hours
of each month the trend had no column for the month the plant was actually in, while the
compliance report beside it windowed the same `created_at` in Tehran. `PlantZoneIsPinnedTest`
(no production code may read the host's zone, and the zone is named once) and
`ManagementReportTrendZoneTest` (the twelve windows are Tehran midnights) keep it that way.

## Every "was this round completed" test means two statuses

`APPROVED` (V4) is a supervisor's sign-off laid on top of `SUBMITTED`, not a different kind of
completion. **Every** report counting delivered work therefore counts both — through
`LogSheetStatus.COMPLETED_STATUSES`, never by naming a status.

This is the failure mode worth stating plainly: a report that kept `status = 'SUBMITTED'` would
show a unit's compliance **falling as its work got reviewed**, with no error anywhere and no
obvious cause. There are about forty such conditions across this codebase;
`CompletedStatusConditionTest` fails the build for any that names `SUBMITTED` alone.

The same applies to `completed_by_user_id` in the personnel report and to the entry-level
severity and data-quality queries, all of which filter on the sheet's status.

## 2. نرخ تحقق و تأخیر — `/reports/compliance`

Grouped by operational unit, separately by template, and — since V19 — **by log-sheet category**:
the category **frozen onto each round** (`log_sheets.category_id` / `category_name`), so a template
moved to another category later does not move the rounds it already raised, and uncategorised
rounds form their own row («بدون دسته»). Same columns and the same formulas as the other two
groupings, over the same unit scope and window (`LogSheetRepository.complianceByCategory`).

| Column | Meaning |
|--------|---------|
| کل | every sheet raised in the window, whatever its state |
| ارسال‌شده | `status IN (SUBMITTED, APPROVED)` — a round a supervisor has approved is still a delivered round, and a report that counted only `SUBMITTED` would show a unit's compliance **falling** as its work got reviewed |
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
- **A round whose deadline passed with a saved draft counts as منقضی, not ارسال‌شده.** The expiry
  scheduler used to auto-submit those; it no longer does
  ([jobs.md](jobs.md#log-sheet-expiry)). The readings are not lost — they are in
  `log_sheet_entries` and shown on the sheet's own page as «N از M دارایی» — but nobody submitted
  the round, and the rate now says so. Expect this figure to fall at any site that was relying on
  the old behaviour, and read the drop as the correction it is.
- **A sheet with no `due_at` is neither on-time nor late.** It cannot be judged against a
  deadline it never had, so it is absent from both columns (though still counted in کل).

**میانه تأخیر / صدک ۹۰** are computed over the *overshoot only* — sheets finished early are
dropped rather than entered as negative lateness, which would let punctual work cancel out
overdue work and hide the problem. Percentile = nearest-rank on the sorted samples.

## 3. تخطی از بازه مجاز — `/reports/exceptions`

Every submitted reading that breached its threshold, danger first, newest first.

Reads the stored `log_sheet_entries.max_severity` / `breached_fields`, which are computed **at
write time** against the thresholds captured in that sheet's `field_definitions_snapshot` — the
ones in force when the reading was taken, so re-tuning never rewrites history. One row per
offending field, so an entry breaching two parameters appears twice.

**Only `SUBMITTED` and `APPROVED` rounds are listed** (`findBreachedEntries` filters on the
sheet's status), so **voiding a round removes its breaches from this page immediately** — the
filter is on the round, and the entry's own `max_severity` is never rewritten, so un-voiding
brings them straight back. The alarms derived from those same readings are recomputed too, but
*after* the commit on the alarm thread, so for a few seconds this page and the alarm queue
disagree and the queue is the one catching up ([alarms.md](alarms.md)).

**"Threshold" means two different rules, by field type.** `FieldValidationSupport.evaluateValue`
dispatches on `dataType`:

| Type | Rule |
|---|---|
| `number` | the reading falls **outside** `danger`, else outside `warning` |
| `select` | the chosen option is **named in** `dangerOptions`, else in `warningOptions` |
| `multiselect` | the **worst** of the options chosen, by the same rule — one dangerous tick among five safe ones is a danger |
| anything else | always `OK` — a photo, a note and a GPS fix are not in or out of range |

**And a threshold may apply only in one state of the asset.** A parameter carrying a `conditions`
gate is judged by the first condition whose `when` holds, and is `OK` — *suppressed by design* —
when none does ([log-sheets.md](log-sheets.md), [schema.md](schema.md)). This page passes the
entry's **whole `form_data`** to `evaluateValue` for that reason: without it the page would
re-judge a suppressed reading as a breach while `breached_fields`, written by the gated
evaluation, does not list it — two descriptions of the same reading, with the stored one right.
`summaryFa` therefore prints the condition inside the «بازهٔ مجاز» column rather than beside it,
so «خطر: ≥ 4.5» never appears as though it applied unconditionally.

A select field used to be judged by the numeric rule, which answers `OK` for anything that is
not a number. So however an administrator configured one, it was invisible here, in
**تخطی‌های امروز** on the dashboard, and to every external poller reading the column — all three
read the same stored value, which is why making the evaluation type-aware fixed them together.

**Filters and paging.** The page takes `days`, `unitId`, `dangerOnly`, `page` and `size`
(25 / 50 / 100 / 250, default 50):

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
| سلامت تگ‌های NFC | assets with `status = OPEN` fault reports, **oldest first** — it is a maintenance queue, not a leaderboard. Marking a report بررسی‌شده removes it from this queue. Paged in SQL, own page number `nfcPage` |
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

**Two pagers on one page, and they are independent.** سلامت تگ‌های NFC steps through `nfcPage`,
دارایی‌های بدون قرائت through `page`; both share `days` and `size`. Each pager's links carry the
other section's current page so moving one never moves the other — an operator works one queue at
a time, and having the section they were not looking at jump underneath them is how a row gets
skipped. `nfcPage` is clamped to the same 250 ceiling as everything else.

## 5. نیروی انسانی و بار کاری — `/reports/workforce`

| Column | Formula |
|--------|---------|
| تکمیل‌شده | completed sheets (`SUBMITTED` **or** `APPROVED`) where `completed_by_user_id = this user` — approving a round does not take the completion away from whoever walked it |
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

### When a parameter is retired, what happens to the readings taken under it

A class's parameter list changes over time. The readings already recorded do not move — they sit
in `log_sheet_entries.form_data`, keyed by whatever the parameter was called at the time.

**The report iterates the stored `form_data`, not the class's current list**, so a value is never
dropped from the table:

```java
for (Map.Entry<String, Object> entry : reading.formData().entrySet()) {
    FieldDefinition fd = defsByKey.get(entry.getKey());
    String label = fd != null && fd.getLabel() != null ? fd.getLabel() : entry.getKey();
    String unit  = fd != null ? fd.getUnit() : null;
}
```

But the *decoration* — the Persian label, the unit, the severity colouring and the chart — comes
from a definition, and whether one is found is the whole question.

**Retire a parameter by disabling it, and everything is found.** `fieldDefinitionsForAsset`
resolves through `LogSheetFieldDefinitionsService.resolveForReporting`, which carries a disabled
definition through *marked* instead of dropping it:

| Part of the report | Disabled (`field_definitions.deleted`, or the asset's `"removed": true`) | Hard-deleted |
|---|---|---|
| **Value history table** | ✅ full label, unit and severity, with a «غیرفعال» badge on the row | ⚠️ appears, but as a raw key with no unit and `OK` for whatever it was |
| **Parameter dropdown** | ✅ offered, suffixed «(غیرفعال)» | ❌ absent — cannot even be selected |
| **Trend chart** | ✅ draws, with its threshold lines | ❌ `buildChartSeries` returns empty |
| **New rounds** | not offered — it is not captured into the next sheet's snapshot | not offered |

This is the one caller that wants disabled definitions. Everything that produces a *form* —
generation, the bundle, the fill page, severity — resolves through `flattenedByClass` and must
keep doing so, because for them "switched off" means "not on the form".

`resolveForReporting` resolves twice, once honestly and once with every switch flipped on; the
difference is, by construction, exactly what is disabled — so the report can never disagree with
the live path about what a parameter *is*. It costs the same two queries as the live path.

A disabled parameter nobody ever answered is listed too. Filtering on "did any reading use this
key" would cost a jsonb scan per page load, and the case barely arises: a parameter with no
readings can be deleted outright, which is exactly what the class page still offers.

**The hard-delete row of that table is now hard to reach.** `AssetClassWebController.deleteField`
refuses once a reading, a superseded reading or an asset's own band names the key, and points at
«غیرفعال» instead. The three attachment readings on the operational database that belong to
no definition date from before that guard; re-creating `Audio` and `Video` on the Pumps class
**as disabled parameters** reattaches them — matching is by key — with no migration and nothing
touched.

**A log sheet was never affected either way.** Each sheet carries `field_definitions_snapshot`,
frozen when it was generated, so an old sheet renders its parameters with their proper labels
whatever has happened to the class since. Before disabling existed, the sheet and the report
therefore disagreed about the same reading — the sheet showed «دمای ورودی», the report showed
`inlet_temp`. With a disabled parameter they agree.

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

### Paging the long reports

Three report lists return rows rather than an aggregate, and all page **in the database**:

| Report | Page sizes | Shape |
|---|---|---|
| تخطی از بازه مجاز | 25 / 50 / 100 / 250 | `outOfRangePage(...)` — count and slice are separate queries |
| کیفیت داده → دارایی‌های بدون قرائت | 25 / 50 / 100 / 250 | `assetsWithoutRecentReadingsPage(...)` |
| کیفیت داده → سلامت تگ‌های NFC | 25 / 50 / 100 / 250 | `openNfcFaultsPage(...)` — group by asset, order by `MIN(created_at)`, then a second query for the reports of the assets on that page |

The order is filter → rank → count → slice, and it has to stay that way. **Ranking a page that
was already fetched answers a different question on every page**, and counting fetched rows
silently stops growing once a cap is reached — a mistake this report family has made before (see
the overview's breach figures above). Both counts are `SELECT count(*)` over the filtered set,
never `rows.size()`.

Page size is capped server-side (250 for silent assets and the NFC queue, `OUT_OF_RANGE_ROW_LIMIT`
for breaches):
the pager is how somebody sees more, not a bigger page, or one request pulls the whole registry
into memory. Changing the window or the page size returns to page one — page five of the old
filter is a different set of rows under the new one.

> **دارایی‌های بدون قرائت used to stop at 100 rows with no way past it.** On a plant with more
> silent assets than that, the equipment beyond the cap was invisible in the report whose entire
> purpose is to surface equipment nobody has read. It also once examined a bounded slice (4× the
> display limit) and ranked it in Java; both are gone — the ranking, the count and the slice are
> all SQL now. `DataQualityReportIntegrationTest` pins it.

> **سلامت تگ‌های NFC had no ceiling at all.** `openNfcFaults()` loaded every open report in
> scope and built one row per asset in Java, so the cost of opening کیفیت داده grew with the
> number of faults the plant had never closed — the one number that rises precisely when the
> report matters most. It now pages: `findAssetIdsWithOpenFaults(...)` groups by asset and orders
> by `MIN(created_at)` inside the page query, `countAssetsWithOpenFaults(...)` gives the total,
> and only then are the reports for that page's assets fetched.
>
> **The order of the page query is not the order of the second query's result.** Fetching the
> reports for those asset ids returns them grouped however the database liked; rebuilding the rows
> by iterating that result reorders the page and the oldest fault stops being first. The rows are
> built by walking `assetIds` — the ordered list — and looking each asset up in the grouped map.
> A test with three assets in reverse age order fails if that is inverted.
>
> The overview's «خرابی NFC باز» figure counts in SQL (`countOpenForUnits`) rather than reading
> `.size()` off a fetched list, for the same reason the breach figures do.


---

## Adding a report

1. Add the method to `ReportWebController` under `@PreAuthorize("hasAuthority('GET:/reports')")`.
2. Resolve scope with `AssetAccessService` — and write **both** variants: a scoped query and an
   unrestricted one. `visibleUnitIds()` returns `null` for an admin, and a CTE binding
   `:unitIds` to null matches nothing, which silently shows an administrator an empty report.
3. Aggregate **in SQL**. Fetching rows to count them in Java stops being correct the moment a
   page cap is involved, and stops being fast immediately.
4. Decide which window rule applies — raised (`created_at`) or read
   (`COALESCE(completed_at, submitted_at)`) — and say so in the table above. If the page shows
   both kinds of number side by side, read §0's note on why the dashboard puts them both on the
   round's window: a reader divides two numbers printed next to each other, so they have to
   describe the same population.
5. Clamp any user-supplied limit server-side. An enumerated parameter (a period, a column name)
   is **refused** when it is not recognised rather than coerced to a default — `LogSheetDateField`
   and `DashboardPeriod` both do this, and both record why.
6. Test with a **scoped** user. An admin passes almost any scope bug, because their scope is
   everything.
7. Document the formula here, in the same commit.

## Related

- **[schema.md](schema.md)** — the tables and the partial indexes these queries rely on
- **[hierarchy.md](hierarchy.md)** — registry scope vs reporting scope, and why reports use the latter
- **[log-sheets.md](log-sheets.md)** — where the measured data comes from
- **[security.md](security.md)** — the scope rules that decide which rows a report may count
