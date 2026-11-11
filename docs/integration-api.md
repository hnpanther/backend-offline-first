# Integration API — the guide for a third-party system

> **Who this is for.** Whoever is writing the client: an ERP, a CMMS, a historian, a BI loader, a
> plant-wide dashboard. It is self-contained — nothing here assumes you have read the rest of
> `docs/`, seen the database, or can read Persian. Everything a consumer must know to pull
> finished log sheets correctly is in this one file, including the parts that are easy to get
> wrong.
>
> **What it is.** One read-only HTTP surface: **finished log sheets**, by date range or by id.
> `GET` only, JSON only, authenticated by an API key on its own filter chain — no user account,
> no session, no OAuth.
>
> For the reasoning behind the design (why a separate filter chain, why only finished sheets, why
> the window is half-open) see [security.md §7](security.md), [log-sheets.md §7](log-sheets.md)
> and the Javadoc on `IntegrationLogSheetQuery`. This file is the contract; those are the
> arguments.

---

## Contents

1. [At a glance](#1-at-a-glance)
2. [What a log sheet is](#2-what-a-log-sheet-is)
3. [Your key](#3-your-key)
4. [Conventions](#4-conventions)
5. [`GET /log-sheets` — the list](#5-get-log-sheets--the-list)
6. [Choosing which rounds you receive](#6-choosing-which-rounds-you-receive)
7. [`GET /log-sheets/{id}` — the detail](#7-get-log-sheetsid--the-detail)
8. [Values, by field type](#8-values-by-field-type)
9. [Attachments](#9-attachments)
10. [Errors](#10-errors)
11. [Recipes](#11-recipes)
12. [Four things that will catch you out](#12-four-things-that-will-catch-you-out)
13. [Limits and etiquette](#13-limits-and-etiquette)
14. [What this API will not do](#14-what-this-api-will-not-do)
15. [Compatibility promise](#15-compatibility-promise)
16. [Go-live checklist](#16-go-live-checklist)

---

## 1. At a glance

| | |
|---|---|
| Base path | `/integration/v1` |
| Authentication | `X-API-Key: lsk_<keyId>_<secret>` header, on every request |
| Methods | `GET` only |
| Content type | `application/json; charset=UTF-8` |
| Language | Field names and error codes are English. **Data is Persian** — labels, names, units and free text are the plant's own, in Persian, UTF-8 |
| Timestamps | ISO-8601 in **UTC**, e.g. `2026-08-19T07:12:33Z` |
| Paging | `page` (zero-based) + `size`; `size` defaults to 50 and is capped (200 as shipped) |
| Ordering | By completion instant ascending, then by id — stable across pages |
| Rate limit | None enforced today, but **every request is logged and attributed to your key** |

```bash
curl -s -H "X-API-Key: lsk_a1b2c3_d4e5f6" \
  "https://plant.example/integration/v1/log-sheets?from=2026-08-01&to=2026-09-01"
```

Two endpoints, and that is the whole API:

| Method | Path | Returns |
|---|---|---|
| `GET` | `/integration/v1/log-sheets` | one page of finished log sheets |
| `GET` | `/integration/v1/log-sheets/{id}` | one finished log sheet, in full |

---

## 2. What a log sheet is

A **log sheet** is one round of recorded work with a deadline and somebody responsible for it.
Two quite different things wear that name, and which one you want decides how you call this API.

**An equipment round.** A template says "every pump in Unit 01, daily". The system raises a sheet
carrying **one row per asset** — a pump, a valve, a compressor — and the operator walks the plant
with a tablet, identifies each asset (by NFC tag or QR code), and records that asset's
parameters: temperature, pressure, a photograph, a voice note.

**A questions-only round.** The same machinery with no equipment in it: a shift handover, an area
inspection, a safety patrol, a questionnaire. It has no asset rows at all; its content is a set
of questions belonging to the round itself.

A round may also be **both** — assets *and* its own round-level questions.

The vocabulary you will meet in the JSON:

| Term | What it is |
|---|---|
| **Template** | The recurring definition a round is raised from. A round may have none (a one-off "custom" sheet), in which case `templateId` is null and `templateName` still describes it |
| **Operational unit** | The organisational unit **responsible** for the round. This is the first thing most consumers filter on |
| **Asset** | One piece of equipment at a functional position, with a plant asset code and usually an NFC tag id |
| **Asset class** | The kind of equipment — pump, valve, motor. **The class owns the parameter definitions**, which is why rounds are naturally filtered by class |
| **Field / parameter** | One question: a key, a Persian label, a data type, sometimes a unit of measure |
| **Entry** | One asset's row on one round: that asset's identity plus the values recorded against it |
| **Sheet form** | The round's own questions and answers, asked once for the whole round rather than per asset |
| **Severity** | The result of checking a reading against its configured ranges: `OK`, `WARNING`, `DANGER`. This is usually the field an external maintenance system acts on |

**Only finished rounds leave through this API.** A round still being walked is half-recorded work
— its values are whatever the operator has typed so far, some of its assets may be untouched, and
its status will change with no event you can observe. Asking for an in-flight status is a **400**,
not an empty page.

| Status | Meaning |
|---|---|
| `SUBMITTED` | Completed by the operator and received by the server |
| `APPROVED` | Completed **and** reviewed by a supervisor. More trustworthy than `SUBMITTED`, not a different outcome |
| `VOIDED` | Completed, then **invalidated**. The readings are not to be trusted — see [§12](#12-three-things-that-will-catch-you-out) |
| `EXPIRED` | The deadline passed with nothing recorded |
| `CANCELLED` | Called off before it was done |

`PENDING`, `ASSIGNED` and `IN_PROGRESS` exist in the plant and are never returned here.

---

## 3. Your key

A plant administrator issues the key from the admin panel, one live key per client system.

* The format is `lsk_<keyId>_<secret>`. **It is shown once.** Only a hash is stored, so a lost key
  cannot be recovered — it is revoked and replaced.
* Send it as `X-API-Key` on every request. Never in a query string, never in a URL you log, and
  never from a browser: the key is a server-to-server credential and this API sends no CORS
  headers, so a page cannot call it anyway.
* Keys may carry an expiry date. **An expired key cannot be extended** — the plant revokes it and
  issues a new one, which is a new secret. Plan for rotation: read the key from configuration or
  a secret store, not from source.

**Every rejection is the same `401`.** Missing, malformed, unknown, wrong secret, disabled,
revoked, expired — one answer:

```json
{ "error": "unauthorized",
  "message": "A valid, active API key is required. Send it in the X-API-Key header." }
```

That is deliberate, and it affects how you debug: the API will not tell you *which* of those
happened. The plant can — every request, accepted or refused, is recorded against the key with
its path, query string, outcome, row count, duration, IP and user agent, and an administrator can
read it on the key's own page. **When something is wrong with your key, ask them to look; they can
see what you cannot.**

Two consequences worth stating plainly:

* Your query strings are visible to the plant. They contain no secret (the key is a header,
  structurally unable to appear there) but they do show what you are pulling and how often.
* A burst of `401`s from your address looks exactly like somebody guessing keys. If you are
  rotating or testing, tell them first.

---

## 4. Conventions

**Timestamps.** Every instant is ISO-8601 UTC with a `Z` — `2026-08-19T07:12:33Z`. Convert to the
plant's local zone for display if you must; the wire is always UTC.

**Ids.** `id` values are stable database identities and are safe to store as your correlation key.
People are **not** identified by id: a person is `{ username, fullName, personnelCode }`, and
`personnelCode` is the plant's own identifier for that human — the field your HR or ERP system
already holds, and the correct one to join on.

**Nulls are information.** A parameter the operator left blank is published as `value: null`
rather than omitted, because "nobody answered this" is a fact an inspection record has to carry.
Treat a present-but-null value and an absent key as different things; this API only produces the
first.

**The date range is half-open: `[from, to)`.** `from` is inclusive, `to` is exclusive. So
`from=2026-08-01&to=2026-09-01` is exactly August, and yesterday's `to` can be reused verbatim as
today's `from` with no overlap and no gap. That property is the reason the API is shaped this way,
and it is what makes incremental pulls safe.

**Accepted date formats** for `from` and `to`:

| Format | Example | Interpreted as |
|---|---|---|
| ISO instant | `2026-08-01T00:00:00Z` | exactly that instant — **prefer this** |
| ISO with offset | `2026-08-01T00:00:00+03:30` | exactly that instant |
| Local date-time | `2026-08-01T00:00:00` | that wall-clock time in the plant zone |
| Date | `2026-08-01` | midnight in the plant zone |

The plant zone is server configuration (`Asia/Tehran` as shipped). The last two formats depend on
it, which is why the first two are preferred: they mean the same thing wherever either side runs.

**Versioning.** The version is in the path (`/v1`). A breaking change gets a new path; see
[§15](#15-compatibility-promise) for what counts as breaking.

---

## 5. `GET /log-sheets` — the list

Finished log sheets whose completion instant falls in `[from, to)`, one page at a time, with
enough on each row to decide whether to fetch the detail.

### Parameters

| Parameter | Required | Takes | Default |
|---|---|---|---|
| `from` | **yes** | one date-time | — |
| `to` | **yes** | one date-time | — |
| `statuses` | no | list of `SUBMITTED` `APPROVED` `VOIDED` `EXPIRED` `CANCELLED` | `SUBMITTED,APPROVED` |
| `unitId` | no | list of ids | every unit |
| `templateId` | no | list of ids | every template, including the custom sheets that have none |
| `assets` | no | `WITH` / `WITHOUT` / `ANY` | `ANY` |
| `classId` | no | list of ids | any class |
| `assetId` | no | list of ids | any asset |
| `scanMode` | no | list of `NFC` `QR` `NFC_OR_QR` `NONE` | any mode |
| `origin` | no | `SCHEDULED` / `MANUAL` | either |
| `severity` | no | list of `OK` `WARNING` `DANGER` | any |
| `page` | no | integer, zero-based | `0` |
| `size` | no | integer | `50`, capped at `200` |

**Every list-valued parameter takes one value or several, separated by commas.** `unitId=3` and
`unitId=3,4,5` are both valid; names are case-insensitive; a trailing comma is ignored. At most
**100** values in one parameter.

**All filters combine with AND.** Within one parameter the values are OR: `scanMode=NFC,QR` is
"either mode", and adding `severity=DANGER` narrows that to "either mode **and** something was
flagged".

### Which rows come back

Three rules decide the window, and all three matter:

1. **Only finished statuses**, whatever you ask for. Requesting `IN_PROGRESS` is a 400.
2. **The default is `SUBMITTED,APPROVED`** — completed rounds. Approval is a review step on top of
   completion, so an approved round belongs in "completed"; leaving it out would mean every
   consumer silently stopped receiving rounds the day the plant switched approval on. Voided,
   expired and cancelled rounds must be asked for **by name**, so that nobody imports invalidated
   readings by accident.
3. **Which timestamp the window matches depends on the status**, because "when did this finish" is
   a different column per outcome:

   | Status | Matched on |
   |---|---|
   | `SUBMITTED`, `APPROVED`, `VOIDED` | the operator's completion instant |
   | `EXPIRED` | the instant the deadline passed |
   | `CANCELLED` | the instant it was called off |

   Every row echoes the instant that matched as **`finalizedAt`**, so you never have to reproduce
   that rule. Order it, compare it, store it — it is the one timestamp that is always populated
   and always the one the filter used.

### Ordering and paging

Rows are ordered by `finalizedAt` ascending, then by `id` ascending. The tie-break is not
decoration: two rounds can finish in the same millisecond, and without it a page boundary could
repeat or skip a row.

Ascending is deliberate — it is what an incremental pull needs. For "the most recent", narrow the
window instead of reversing the order (`from` = an hour ago).

`size` is clamped rather than refused, and **the response echoes the size actually applied**, so
asking for 100000 and receiving `"size": 200` is how you learn the cap exists. Both numbers are
per-installation configuration; read them off the response rather than hard-coding them.

### Response

```json
{
  "items": [
    {
      "id": 3,
      "templateId": 1,
      "templateName": "لاگ شیت پمپ های واحد 01",
      "scopeSummary": "location:180",
      "status": "SUBMITTED",
      "origin": "MANUAL",
      "scanMode": "NFC",
      "unit": { "id": 3, "code": "OP-UNIT-01", "name": "واحد عملیاتی 01" },
      "dueAt": "2026-08-14T14:13:34.374Z",
      "completedAt": "2026-08-14T14:06:04.467Z",
      "submittedAt": "2026-08-14T14:06:04.467Z",
      "finalizedAt": "2026-08-14T14:06:04.467Z",
      "approvedAt": null,
      "approvedBy": null,
      "assignedTo": { "username": "a.saljooghi", "fullName": "احمد سلجوقی", "personnelCode": "4996" },
      "completedBy": { "username": "a.saljooghi", "fullName": "احمد سلجوقی", "personnelCode": "4996" },
      "operatorName": "احمد سلجوقی",
      "assetCount": 47,
      "attachmentCount": 2
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 15,
  "totalPages": 1,
  "hasNext": false
}
```

| Field | Notes |
|---|---|
| `id` | What the detail endpoint takes. Stable |
| `templateId` / `templateName` | `templateId` is null for a custom, template-less round; the name still describes it |
| `scopeSummary` | Free text describing what the round covered. For humans, not for parsing |
| `status` | One of the five finished statuses |
| `origin` | `SCHEDULED` (raised by the scheduler) or `MANUAL` (raised by a person) |
| `scanMode` | How this round was required to identify its assets — **frozen when the round was raised**, so a template changed later does not rewrite history. `NFC`, `QR`, `NFC_OR_QR` or `NONE`. Every round raised before the setting existed answers `NFC`, which is what it was |
| `unit` | The responsible operational unit. `code` is what an ERP joins on |
| `dueAt` | The deadline, if the round had one |
| `completedAt` | When the operator finished. **Device time** — it may precede `submittedAt`, and on a round finished offline it may precede it by days |
| `submittedAt` | When the server received it |
| `finalizedAt` | The instant the window matched on. Always populated |
| `approvedAt` / `approvedBy` | When a supervisor accepted the round, and who. Null if nobody has. **Not the same instant as the window** — see [§12](#12-three-things-that-will-catch-you-out) |
| `assignedTo` | Who the round was assigned to, if anyone |
| `completedBy` | Who completed it. Null for an unattended expiry |
| `operatorName` | Free-text operator name captured on the device, when present |
| `assetCount` | Asset rows in the detail. **Zero is meaningful**: a questions-only round. One row exists per asset the round covered whether or not the operator reached it, so this is the size of the round, not the work done |
| `attachmentCount` | Photos and voice notes on the live round |

Page envelope: `items`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`. Loop on
`hasNext` and you need no arithmetic.

**Nothing internal appears here.** Sync bookkeeping, draft state, supervisor notes, the frozen
schema and every internal user id are deliberately absent; the schema is published properly by
the detail endpoint instead.

---

## 6. Choosing which rounds you receive

This is the section worth reading twice, because the default — every finished round in the window
— is almost never what a given consumer wants.

### `assets` — equipment rounds or questionnaires

| Value | Returns |
|---|---|
| `WITH` | only rounds carrying at least one asset |
| `WITHOUT` | only rounds carrying none: the questions-only rounds |
| `ANY` *(default)* | both |

Judged on the round's **own asset rows**, not on what its template intended — which is both the
simpler question and the truthful one, since a custom round has no template at all.

`assets=WITHOUT` is how you subscribe to questionnaires, handovers and patrols. `assets=WITH` is
how an equipment-facing system stops receiving them.

### `classId` — by kind of equipment

Matches a round that covered **at least one** asset of one of the named classes, taken from the
class frozen onto the asset row when the round was raised. `classId=4,9` is "pump rounds or valve
rounds".

Note what it does *not* mean: a round that covered pumps **and** valves matches `classId=<pump>`,
and the detail will still contain the valves. Filter the rows yourself if you want only one
class's readings out of a mixed round — every asset row carries `asset.className`.

### `assetId` — one machine's history

Matches a round that covered at least one of the named assets. This is the "everything ever
recorded about compressor K-101" query, bounded by the date window.

### `severity` — the rounds somebody should look at

Matches a round where **at least one asset** recorded one of the named severities.
`severity=WARNING,DANGER` is "rounds with a problem", and it is the single most useful filter for
a maintenance system.

It is an exact set, not a floor: `severity=OK` means "something was read and it was within range",
**not** "no restriction".

An asset nobody reached has no severity at all, so:

* a round whose assets were all missed matches **no** `severity` value, while it does match
  `assets=WITH`;
* a questions-only round can never match `severity`, because severity is recorded against assets.

### `scanMode` — how the round was walked

The mode frozen onto the round: `NFC`, `QR`, `NFC_OR_QR` or `NONE`. Useful mostly for data quality
— it is the only thing that makes `entrySource` on the detail interpretable. A round in `QR` mode
cannot produce an `PWA_NFC` reading, and a `PWA_MANUAL` reading on a round that was *meant* to be
scanned is a different fact from the same value on a round where nothing was ever meant to be
scanned.

### `origin`, `unitId`, `templateId`

`origin=SCHEDULED` is the routine programme; `origin=MANUAL` is what somebody raised by hand.
`unitId` and `templateId` take lists and are the cheapest filters there are.

### Combinations that are refused

`assets=WITHOUT` with `classId`, `assetId` or `severity` is a **400**. Each of those three selects
rounds by something recorded against an asset, and a round with no assets has nothing to match —
the combination describes nothing. It is refused rather than answered with an empty page because a
poller on an impossible filter sees a permanently quiet feed and concludes the plant has nothing
to report.

### A note on unknown ids

A well-formed id that matches no row is **not** an error: `unitId=99999999` answers an empty page.
That is deliberate — refusing unknown ids would turn every filter into a probe for which units,
templates, classes and assets this plant has. A value that is not a number, or is zero or
negative, **is** refused, because that is a client bug and the empty page it would otherwise
produce looks exactly like a quiet plant.

---

## 7. `GET /log-sheets/{id}` — the detail

One finished round in full: the header, the parameter schema frozen when it was raised, every
asset row with its values, and the round's own questions if it had any.

```bash
curl -H "X-API-Key: lsk_a1b2c3_d4e5f6" \
  https://plant.example/integration/v1/log-sheets/3
```

```json
{
  "summary": { "id": 3, "status": "SUBMITTED", "scanMode": "NFC", "…": "exactly the list row" },
  "fields": [
    { "key": "temp", "label": "دما", "dataType": "number", "unit": "°C",
      "required": false, "classId": 1, "order": 1 }
  ],
  "assets": [
    {
      "asset": {
        "id": 1, "code": "ASET-PK-0102-P2", "name": "Corrosion Inhibitor Hand Pump",
        "className": "Pump", "subFunctionCode": "PK-0102-P2",
        "subFunctionTag": "PK-0102-P2", "nfcTagId": "PK-0102-P2"
      },
      "values": [
        { "key": "temp", "label": "دما", "unit": "°C", "dataType": "number",
          "value": 72.5, "attachments": [] },
        { "key": "pressure", "label": "فشار", "unit": "bar", "dataType": "number",
          "value": null, "attachments": [] },
        { "key": "photo", "label": "عکس", "unit": null, "dataType": "image",
          "value": null,
          "attachments": [
            { "id": "b94ecdd6-…", "kind": "IMAGE", "mimeType": "image/jpeg",
              "sizeBytes": 20481, "width": 1280, "height": 960,
              "durationMs": null, "capturedAt": "2026-08-14T14:08:00.230Z" }
          ] }
      ],
      "maxSeverity": "WARNING",
      "breachedFields": ["temp"],
      "filledAt": "2026-08-14T14:05:11.023Z",
      "filledBy": { "username": "a.saljooghi", "fullName": "احمد سلجوقی", "personnelCode": "4996" },
      "entrySource": "PWA_NFC"
    }
  ],
  "sheetForm": null,
  "expiredAt": null,
  "cancelledAt": null
}
```

### `summary`

Byte-for-byte the shape the list returns, so one type in your code serves both endpoints.

### `fields` — the frozen schema

The parameter definitions **as they stood when the round was raised**. Published for two reasons:
you can label and unit-annotate the values without holding a copy of the plant's field catalogue,
and a definition edited afterwards cannot retroactively change what a historical reading appears
to mean.

| Field | Notes |
|---|---|
| `key` | The identifier the values are keyed by. Unique within a class |
| `label` | Persian display label |
| `dataType` | See [§8](#8-values-by-field-type) |
| `unit` | Unit of measure, when the parameter has one |
| `required` | Whether the operator had to answer it |
| `classId` | The asset class this parameter belongs to — **a round may span several classes** |
| `order` | Display order within its class |

A round covering pumps and valves carries both classes' definitions in one list. Each asset row's
`values` is already narrowed to that asset's own class, so you rarely need `classId` yourself.

### `assets` — one row per asset

In the order the round carries them.

| Field | Notes |
|---|---|
| `asset.id` | Row id of the asset — what `assetId` on the list takes |
| `asset.code` | The plant asset code |
| `asset.name` | **The name frozen on the row**, not today's name on the asset. A round is a record of what was inspected; renaming equipment afterwards must not rewrite history |
| `asset.className` | The class name, for labelling |
| `asset.subFunctionCode` / `subFunctionTag` | The functional position the asset sits at |
| `asset.nfcTagId` | The tag id of that position. A QR code on the same position carries this same value — one identifier, two ways to read it |
| `values` | One row per parameter of this asset's class, in schema order |
| `maxSeverity` | Worst severity across this asset's values: `OK`, `WARNING`, `DANGER`. **Null means nothing was evaluated** — this asset was not read |
| `breachedFields` | The keys that breached, most severe first |
| `filledAt` | Device time of the last edit to this asset's values |
| `filledBy` | Who recorded them |
| `entrySource` | How the asset was reached: `PWA_NFC` (tag scanned), `PWA_QR` (QR scanned), `PWA_MANUAL` (picked by hand on the tablet), `WEB` (typed in the panel). Reported by the client, not witnessed by the server — it is a record of what the operator did, not a credential |

`PWA_NFC` and `PWA_QR` are separate values on purpose: a chip read can be checked against a
hardware serial and a QR read cannot, so a report that merged them would overstate what half its
rows prove. The chip's hardware serial itself is never published — it is an anti-cloning control,
and a published control is not one.

### `sheetForm` — the round's own questions

Present when the round asked anything of itself, and **absent (`null`) otherwise**, which is most
rounds. Its own component rather than keys spliced into every asset row, for two reasons that are
both about you: a round-level answer repeated against forty assets is forty copies of one fact,
and the person who answered the round's questions is not necessarily the one who read a given
gauge.

```json
"sheetForm": {
  "fields": [ { "key": "shift", "label": "شیفت", "dataType": "text",
                "unit": null, "required": true, "classId": null, "order": 1 } ],
  "values": [ { "key": "shift", "label": "شیفت", "unit": null,
                "dataType": "text", "value": "شب", "attachments": [] } ],
  "filledAt": "2026-08-14T14:02:00.000Z",
  "filledBy": { "username": "a.saljooghi", "fullName": "احمد سلجوقی", "personnelCode": "4996" },
  "source": "MOBILE"
}
```

`fields` and `values` are the same shapes as the per-asset side — one question is one question,
whatever owns it — and `classId` is always null, because a round-level question belongs to no
asset class. `source` is `WEB` or `MOBILE`.

A questions-only round therefore has `assets: []`, `assetCount: 0` and all of its content in
`sheetForm`. **A consumer that only reads `assets` will read such a round as empty.**

### `expiredAt` / `cancelledAt`

Populated for exactly those outcomes, null otherwise. (`finalizedAt` on the summary already holds
whichever instant applies, so you rarely need these.)

### 404

An id that does not exist and an id whose round is **not finished** answer the same `404`, and the
two are deliberately indistinguishable: telling a caller "that round exists but you may not see it
yet" turns the endpoint into a probe for which ids are live work.

---

## 8. Values, by field type

`value` is the reading **as it was stored** — a number stays a number, a boolean stays a boolean.
It is `null` for a parameter nobody answered, and `null` for an attachment-typed parameter, whose
content is described by `attachments` instead.

| `dataType` | `value` on the wire | Notes |
|---|---|---|
| `number` | JSON number | May be integer or fractional. This is the type `severity` is evaluated on |
| `text` | string | |
| `textarea` | string | Long free text |
| `select` | string | One of the configured options |
| `multiselect` | array of strings | The chosen options |
| `checkbox` | boolean | A value stored by an older client may arrive as `"true"` / `"false"` / `"1"` / `"0"` — accept both |
| `location` | object | `{ "type": "location", "lat": 35.6892, "lng": 51.3890, "accuracy": 12.5, "capturedAt": 1786105032313 }`. `accuracy` is metres and `capturedAt` is epoch milliseconds — the one place on this surface that is not ISO-8601, because it is a stored value rather than a field of the contract. Judge a coordinate by its accuracy: in a plant a phone fix can be tens of metres out, which is the difference between "at the pump" and "at the next pump" |
| `image`, `audio`, `video` | always `null` | See `attachments` |

**Be tolerant of an unknown `dataType`.** The plant can add field types; render an unrecognised one
as text rather than failing. The server does the same.

Two things never to do:

* **Do not key your import on labels.** Labels are Persian display text and are edited freely.
  `key` is the identity.
* **Do not assume a key means the same thing across classes.** `ampere` on a pump class and
  `ampere` on a sub-class are two definitions; the `classId` on the field says which is which, and
  each asset row's values are already narrowed to its own class.

---

## 9. Attachments

Attachments are **announced, never served**: id, kind, mime type, size, pixel dimensions, duration
and capture time — no bytes, no base64, no URL and no download endpoint on this API.

```json
{ "id": "b94ecdd6-…", "kind": "AUDIO", "mimeType": "audio/webm",
  "sizeBytes": 8351, "width": null, "height": null,
  "durationMs": 3058, "capturedAt": "2026-08-14T14:08:00.230Z" }
```

| Field | Notes |
|---|---|
| `id` | Stable identifier. Store it: it is what makes the file addressable later without this shape changing |
| `kind` | `IMAGE`, `AUDIO` or `VIDEO` |
| `mimeType` | Verified server-side from the file's magic bytes, never from the uploader's claim |
| `sizeBytes` | Size on disk |
| `width` / `height` | Pixels, for an image or video |
| `durationMs` | Length of an audio or video note |
| `capturedAt` | When it was uploaded |

A photograph on a round with fifty of them would turn a JSON response into a hundred-megabyte
base64 document, and you asked to know that a photo exists — not to receive it. If your use case
genuinely needs the bytes, that is a conversation with the plant, not a parameter.

**An attachment whose file has since been deleted** is published as a record with its `id` and
every other field null. Reported rather than dropped, because silently omitting it would tell you
the operator never took a photograph.

Media attached to a **voided sync** (evidence kept when a round's submission was discarded) is not
visible on this surface at all, as it is not to any reader of the live round.

---

## 10. Errors

Always JSON, always English, always these two fields:

```json
{ "error": "invalid_request",
  "message": "'from' is not a valid date-time: '01/08/2026'. Accepted: 2026-08-01T00:00:00Z, …" }
```

**Branch on `error`, log `message`.** The code is part of the contract; the message is prose for
whoever reads your logs and may be reworded.

| HTTP | `error` | When |
|---|---|---|
| 400 | `invalid_request` | A parameter is missing, unparseable, out of range, unknown, or in an impossible combination. The message names the parameter |
| 401 | `unauthorized` | Anything wrong with the key. One answer for every reason ([§3](#3-your-key)) |
| 404 | `not_found` | No finished round with that id — including an id whose round is still in flight |
| 500 | `internal_error` | Something broke. Never carries exception text, a table name or SQL. Retry with backoff; if it persists, the plant's logs have the detail |

Worth knowing: a 400 is a **client bug you can fix from the message**, and this API prefers one
over a plausible-looking 200. Nothing here coerces a bad value to a default — a dropped filter or
a clamped date is how an integration imports the wrong month for a year and finds out from a
discrepancy in somebody else's report.

Retry policy that matches the server's behaviour:

| Response | Do |
|---|---|
| 200 | proceed |
| 400 | **do not retry** — fix the request |
| 401 | **do not retry in a loop.** Stop, alert, and ask the plant to check the key's usage log |
| 404 | treat as "not available"; do not retry the same id on a schedule |
| 5xx | retry with exponential backoff and a jitter, and keep your window `from` where it was |

---

## 11. Recipes

### Incremental pull — the shape to copy

Keep one cursor: the `to` you last used. Because the window is half-open, it becomes the next
`from` with no overlap and no gap.

```python
cursor   = load_cursor()                  # ISO-8601 UTC, e.g. 2026-08-19T00:00:00Z
boundary = now_utc_iso()                  # or a few minutes in the past; see below

page = 0
while True:
    r = get("/integration/v1/log-sheets", params={
        "from": cursor, "to": boundary,
        "statuses": "SUBMITTED,APPROVED",
        "assets": "WITH",
        "size": 200, "page": page,
    })
    for row in r["items"]:
        detail = get(f"/integration/v1/log-sheets/{row['id']}")
        upsert(detail)                    # keyed on row["id"]
    if not r["hasNext"]:
        break
    page += 1

save_cursor(boundary)                     # only after the whole window succeeded
```

Three details that make it correct:

* **Advance the cursor only when the whole window has been imported.** On any failure, keep the
  old cursor and the next run re-reads the window; your upsert is keyed on `id`, so re-reading is
  free.
* **Leave a small lag on `boundary`** (a minute or two). A round completed offline arrives later
  than it finished, and `completedAt` — the instant the window matches — is device time. A lag
  does not fix that on its own (see [§12](#12-three-things-that-will-catch-you-out)); it just
  stops you racing the current second.
* **Upsert, never insert.** You will legitimately see the same round again.

### Rounds with a problem, for a maintenance system

```
GET /integration/v1/log-sheets
    ?from=2026-08-19T00:00:00Z&to=2026-08-20T00:00:00Z
    &statuses=APPROVED
    &severity=WARNING,DANGER
```

Then, per round, the detail's asset rows where `maxSeverity` is not `OK`, and `breachedFields` for
which parameters caused it. `statuses=APPROVED` means you act only on rounds a supervisor has
accepted — frequently the right choice for anything that raises work orders.

### One machine's history

```
GET /integration/v1/log-sheets?from=2026-01-01&to=2027-01-01&assetId=1042&size=200
```

### Questionnaires only

```
GET /integration/v1/log-sheets?from=2026-08-01&to=2026-09-01&assets=WITHOUT
```

Everything you want is in each detail's `sheetForm`.

### Only pump rounds from two units, scheduled ones

```
GET /integration/v1/log-sheets
    ?from=2026-08-01&to=2026-09-01
    &unitId=3,7&classId=4&origin=SCHEDULED&assets=WITH
```

---

## 12. Four things that will catch you out

**1. A round's status changes after its window has passed.** Approval and voiding happen *after*
completion and do **not** move `completedAt`, so a forward-only poller has already walked past a
round by the time a supervisor approves or voids it. It will never see the change.

> **Re-read a trailing window.** Nightly, re-pull the last 30 days with
> `statuses=SUBMITTED,APPROVED,VOIDED` and reconcile: `approvedAt` tells you a review has happened
> since, and a round you imported as `SUBMITTED` that now reads `VOIDED` is a **retraction** —
> whatever you built on those readings has to be withdrawn too. How far back to re-read is a
> question for the plant: it is however long a round may wait for review, plus a margin.

**2. `completedAt` is device time.** The tablet works offline; a round finished in a cellar on
Monday may reach the server on Wednesday. It is windowed on when the work was *done*, not when it
arrived, because that is what the record means — which also means a round can appear in a window
you have already imported. The trailing re-read above covers this too. `submittedAt` tells you
when the server received it, if you need the difference.

**3. An asset row exists whether or not anybody read it.** A round is raised with one row per asset
in scope and submitted even if some were never reached. So `assetCount` is the size of the round,
not the amount of work done; `maxSeverity: null` and empty `values` mean "not read", not "fine".
If you are computing coverage, count the rows whose `maxSeverity` is non-null.

**4. `OK` does not always mean "inside the band" — a threshold may not have applied.** A parameter
can carry a *state condition*: thresholds that are judged only while another answer on the same
asset says so. «The current must be above the no-load threshold» holds for a running pump and is
meaningless for a stopped one, so on a stopped one the reading is recorded, published, and judged
`OK` — the threshold was **suppressed by design** (ISA-18.2), not satisfied.

> **Do not recompute severity from the values and a threshold list you hold elsewhere.** It was
> true before this existed that `maxSeverity` and `breachedFields` are the authority — they are
> computed against the thresholds *frozen into that round* — and this makes the difference
> visible: your copy of a band would call a 0 A reading a danger on a pump this round knew was
> switched off. Take the published verdict; the values are there for trending.

---

## 13. Limits and etiquette

| Limit | Value |
|---|---|
| Rows per page | `size`, default 50, capped at 200 (per-installation configuration; the response echoes the effective value) |
| Values in one list filter | 100 |
| Requests per minute | not enforced — but see below |
| Response size | bounded by the page cap; no response carries file bytes |

There is no rate limiter today. What there is instead is **a complete, attributed usage log**, so
a client that polls every second is both visible and attributable. Practical guidance:

* Poll as often as the plant agrees to, and no more. For most consumers **every few minutes** is
  ample; a round takes an operator an hour to walk.
* One request per window, paged — not one request per round you suspect exists.
* Pull the list, decide, then fetch details only for what you need. The list exists precisely so
  you do not have to fetch 200 details to find the three that matter.
* Use the filters. A narrow query is cheaper for the plant and for you than a wide one you discard
  client-side, and `severity=WARNING,DANGER` does in the database what would otherwise be 200
  detail fetches.
* Send a meaningful `User-Agent`. It is recorded, and it is how the plant tells your nightly
  loader from your test script.

---

## 14. What this API will not do

Stated plainly, so nobody designs around a feature that is not coming without asking first:

* **No writes.** `GET` only. Nothing external can raise, assign, fill, complete, approve or void a
  round through this surface.
* **No in-flight rounds.** See [§2](#2-what-a-log-sheet-is).
* **No file bytes.** See [§9](#9-attachments).
* **No push, no webhooks, no streaming.** You poll.
* **No user authentication.** An API key cannot reach the mobile API or the admin panel, and no
  user account of any role can reach this API. The separation is the design, not a URL convention.
* **No access to anything but finished log sheets.** No asset catalogue endpoint, no user
  directory, no template listing. If you need the plant's asset list, that is an export, not this
  API.
* **No cross-origin access.** It sends no CORS headers; call it from a server.

---

## 15. Compatibility promise

Within `/v1`:

* **Fields may be added.** New fields appear on existing objects — `scanMode`, `approvedAt` and
  `sheetForm` all arrived this way. **Ignore unknown fields**; a client that fails on one will
  break on a Tuesday for no reason of its own.
* **New optional parameters may be added**, always defaulting to the behaviour that existed before
  them.
* **New enum values may appear** in `status`, `origin`, `scanMode`, `entrySource`, `dataType` and
  `kind`. Handle an unrecognised value as "something new", not as an error.
* **Nothing published is removed or renamed**, and no field's meaning changes, without a new path
  version.
* **Defaults will not change under you.** `statuses` defaulting to `SUBMITTED,APPROVED` is part of
  the contract.

What is *not* promised: the exact wording of a `message`, the order of fields in an object, or the
server's configured page sizes and plant zone.

---

## 16. Go-live checklist

- [ ] Key read from configuration or a secret store, not source. Rotation path agreed with the
      plant, including who to contact.
- [ ] `X-API-Key` sent on every request; the key never logged, never in a URL.
- [ ] Timestamps parsed as UTC; display converted explicitly.
- [ ] Cursor logic uses the half-open window, and advances **only after** a window imports
      completely.
- [ ] Imports are upserts keyed on the round `id`.
- [ ] A trailing-window re-read is scheduled, and a round that becomes `VOIDED` withdraws whatever
      was built on it.
- [ ] `size` read back from the response rather than assumed.
- [ ] `hasNext` drives the paging loop.
- [ ] Unknown fields, enum values and `dataType`s are tolerated.
- [ ] `value: null` distinguished from "field absent"; `maxSeverity: null` understood as "not
      read".
- [ ] 400 and 401 do not retry in a loop; 5xx backs off.
- [ ] Filters chosen deliberately — `assets`, and `severity` if you only act on exceptions.
- [ ] Poll interval agreed with the plant, and a `User-Agent` that identifies your system.

---

## Related

- **[log-sheets.md](log-sheets.md)** §7 — the same API from the inside, with the lifecycle around it
- **[security.md](security.md)** §7 — the filter chain, the key store, and why there is no permission for this
- **[schema.md](schema.md)** — the tables behind every field above
- **[README.md](../README.md)** — issuing and revoking a key in the admin panel
