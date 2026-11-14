# Alarms — a reading that needs a person

> **Built in `V17`.** This file is the reference: what an alarm is, exactly when one is raised,
> kept, cleared and closed, who sees it, and the webhook contract a receiver is written against.
> The tables are in [schema.md](schema.md), the permissions in [security.md](security.md), the
> background passes in [jobs.md](jobs.md). The plan this was built from, and the standards it
> follows, are roadmap §11 and ISA-18.2 / IEC 62682.

---

## 1. What an alarm is, and what it is not

Every reading is already judged when it is written: `EntrySeverityEvaluator` stores
`max_severity` and `breached_fields` on the entry, and the exception report lists every breach.
**That is the event journal, and it stays exactly as it was.** Nobody is asked to do anything
about a row in it.

An **alarm** is different in three ways, all taken from ISA-18.2 / IEC 62682:

1. **It is rationalised.** Only a parameter with an **alarm rule** can raise one, and the rule
   says what the supervisor is expected to do. A parameter with no rule never raises an alarm
   however far out of range it reads. With 47 assets and 22 parameters to a round
   ([roadmap §20](roadmap.md)), an alarm for every warning would fill a queue in a week and teach
   everybody to ignore it.
2. **It is one condition, not one reading.** One live alarm per **(asset × parameter)** — whichever
   rule governs the parameter (V18). A pump reading high on five rounds in a row is one alarm with
   `occurrence_count = 5`; a pump hot *and* over-pressure is two.
3. **It needs a response, and the response is recorded** — acknowledged, and eventually closed
   with a reason.

What it is not: a work order, a form, or a decision. Turning an alarm into follow-up work is a
human act — a follow-up record, as a CMMS notification becomes a work order — and it is not built.

---

## 2. The rules (V18: one rule, many parameters, one default)

`/alarm-rules`, administrators only. Two kinds:

| Kind | Covers | How many |
|---|---|---|
| **قاعده عمومی** — the default (`ALL_FIELDS`) | every parameter of type `number` / `select` / `multiselect` that **no specific rule lists** | at most one (`ux_alarm_rules_one_default`) |
| **قاعده اختصاصی** — specific (`FIELDS`) | the parameters it lists — one or many, picked from a searchable list grouped by class | any number; **a parameter is listed by at most one** (`uq_alarm_rule_fields_field`) |

**Which rule governs a parameter** has exactly one answer: the specific rule that lists it —
**active or not** — and otherwise the default, when it is active. A switched-off specific rule
therefore **silences** its parameters rather than handing them back to the default; that is the
way to exempt a noisy parameter from «three dangers on anything». Taking a parameter *out* of a
specific rule hands it back to the default.

So «every parameter: an alarm after three dangers; bearing temperature: after one warning; this
one flow meter: never» is one default rule and two specific ones.

| Setting | Meaning | Range |
|---|---|---|
| Name | shown in the queue and the webhook; for a specific rule left blank it is derived from its parameters | ≤ 200 chars |
| Parameters (specific only) | field definitions. A rule on a class's field applies to its sub-classes' assets too: an inherited definition keeps its id through flattening. The picker shows each one's class and searches label, key and class; one already in another rule is shown disabled with that rule's name. A parameter switched off (roadmap §23) cannot be **added**, but one switched off after it was listed may **stay** — it reads nothing, so it raises nothing, and refusing it would make the rule uneditable; the picker marks it «(غیرفعال)» | `number`, `select`, `multiselect` — the types that carry a severity |
| Trigger | `DANGER`, or `WARNING` (meaning warning **or worse**) | — |
| Consecutive | how many breaching readings in a row raise it | 1–20 |
| Clear after | how many normal readings in a row clear it | 1–20 |
| Window (days) | optional: the consecutive readings must fall within this many days of the newest one | 1–365, or empty |
| Priority | `HIGH` / `MEDIUM` / `LOW` — orders the queue | — |
| Expected action | **required.** Shown to the supervisor beside the alarm | ≤ 1000 chars |
| Active | switching off **closes** the rule's live alarms (`RULE_DISABLED`) and raises no more | — |

Every value is validated in `AlarmRuleService`, with the CHECKs and unique indexes of `V17`/`V18` as the last line — two administrators saving the same parameter at once both pass the check, and the second is refused whole by `uq_alarm_rule_fields_field` with a message, not a 500. A
rule that has raised an alarm cannot be deleted — its alarms point at it — only switched off.
Editing a rule changes the alarms it will raise; a live alarm keeps the trigger, consecutive and
clear-after it was raised under (frozen onto the alarm row).

**Why «clear after» and not a deadband.** A numeric deadband («back below 85, not merely below
90») only exists for numbers. «N normal readings in a row» works for numbers and options alike,
and is ISA-18.2's off-delay.

---

## 3. When an alarm is evaluated

| Event | Path | Why |
|---|---|---|
| A round is completed on the tablet | `LogSheetService.submitBatch` | the reading is claimed to be true |
| A round is completed on the web | `LogSheetService.completeFromWeb` | same |
| A round is voided | `LogSheetAssignmentService.voidSubmitted` | its readings were invalidated |
| A voided round is restored | `LogSheetAssignmentService.restoreVoided` | they are valid again |

**Not** on progress saves (a draft — it would alarm on typing errors), **not** on approval (no
reading changes; and it is days late), **not** on reopen (the round's readings drop out of the
history while it is being corrected, and evaluating then would withdraw an alarm and re-raise it
a minute later, sending its webhook twice). A corrected round is re-judged when it is completed
again.

### Never inside the round's transaction

Each of those paths does **one** thing about alarms: it inserts a row into
`alarm_evaluation_requests`, which has no unique key and cannot conflict with anything. The
evaluation runs **after the commit**, on a background thread, in its own transaction, and a
failure there is retried from the row — never reported to the operator. An operator's delivered
round must not fail because of a notification about it. A scheduled pass (every 60 s) takes
anything the immediate pass missed: a lost nudge, a stopped process, a failed evaluation
([jobs.md](jobs.md#alarm-evaluation)).

---

## 4. How a condition is judged

The history is one asset's readings of one parameter on **finished** rounds — `SUBMITTED` and
`APPROVED`. Voided rounds were invalidated, expired and cancelled ones recorded nothing, and a
round still being walked has not claimed its readings are true.

It is ordered by the round's `completed_at` — **device time** — never by arrival. A round walked
offline on Thursday and delivered after Friday's sits in Thursday's place.

Each reading is one of three things:

| Kind | Meaning |
|---|---|
| **Breaching** | at or above the trigger. For a `DANGER` rule a `WARNING` is *normal* |
| **Normal** | read, and below the trigger |
| **Not read** | the asset was on the round and the parameter was not answered. **Neither counts nor resets**: «we do not know» is not «it was fine» |

### A parameter whose threshold applies only in one state

A parameter may carry a **state condition** — thresholds judged only while another answer on the
same entry says so ([log-sheets.md](log-sheets.md), [schema.md](schema.md)). «The current band
applies only while the status reads running» is the case it exists for, and it is the ISA-18.2
mechanism for it: *suppressed by design*, as against shelving (the operator's act) and
out-of-service (maintenance's).

**The alarm layer needed no new concept for it, and that is deliberate.** Severity is judged once,
at write, by the gated evaluation, and this layer reads the verdict it stored. So a reading in a
state the condition excludes is **`OK` — normal, not «not read»**, with these consequences worth
knowing:

- A standby machine's rounds **break a run** and **count toward clear-after**, because the
  condition genuinely does not hold on them. For «running but no current» that is right: the
  fault is about the running state, and the machine being stopped ends it.
- An alarm that returns to normal this way still **does not vanish**. Unacknowledged, it stays
  `OPEN` marked `returned_to_normal_at` until somebody looks at it (§4). That existing rule is
  what makes `OK`-on-suppressed safe.
- Treating a suppressed reading as «not evaluated» instead — so it neither counts nor resets —
  would need a third stored list beside `max_severity` and `breached_fields`, because absence
  from `breached_fields` cannot distinguish «fine» from «not judged». It is not built, and
  [roadmap §36](roadmap.md) records the condition that would make it worth a migration.

**«Running and the current not recorded» is not a breach**, and no band can make it one: an absent
reading is «not read», which neither counts nor resets. That gap is closed with `required`, not
with a threshold.

A default rule (`ALL_FIELDS`) therefore costs nothing extra on a gated parameter: in a state the
condition excludes there is no breach to count.

### Only what can change is evaluated

A default rule covers every parameter, and a round is 47 assets × 22 parameters. Asking the history
of all of them on every completion would be a thousand queries per round, so an (asset, parameter)
is evaluated only when something about it can change:

| Situation | Evaluated when |
|---|---|
| it has a **live alarm** | always — any change to its readings may grow, escalate, clear or withdraw it |
| **completed** or **restored**, no live alarm | this round's reading **breaches** the governing rule. A reading that does not breach can only break a run, never make one, wherever in time it lands |
| **voided**, no live alarm | this round's reading was **normal**. Taking a normal reading out of the middle can join two runs into one long enough; taking a breach out never raises |

This round's reading is judged from the verdict stored on its own entry, with no query; all of a
round's live alarms come back in one query, and all rules with their parameters in two more. A round with nothing wrong
costs four queries whatever its size ([performance.md](performance.md)).

**Consequence worth knowing:** a rule created — or tightened — today judges the readings that
arrive from today. It uses the history to count a run, but it is not re-applied to every asset the
moment it is saved; an asset already three dangers deep raises on its next breaching reading.

### Raising

With no live alarm for (asset, parameter), take the readings after the **watermark** (below),
newest first. The **run** is the breaching readings at the head: unread ones skipped, a normal one
ending it, and — with a window — one older than *window* days before the newest breach ending it.
If the run is at least *consecutive* long, an alarm is raised:

| Column | Value |
|---|---|
| `condition_met_at` | the reading at which the run first reached *consecutive* — in a run of five with consecutive 3, the third oldest |
| `first_reading_at` / `last_reading_at` | the oldest and newest breaching readings |
| `occurrence_count` | the length of the run |
| `severity` | the worst reading in it |
| `operational_unit_id` | the unit responsible for the newest breaching reading's round — and **fixed** from then on, so an asset read by two units' rounds does not move between their queues |

### Keeping a live alarm true

Each evaluation recounts every breaching reading since `first_reading_at`:

- **More breaches** → `occurrence_count` grows, `last_value` moves, an `OCCURRED` event.
- **A worse one** → `ESCALATED`; an **acknowledged alarm goes back to `OPEN`** (an acknowledgement
  of a warning is not one of a danger), and the webhook sends `ALARM_ESCALATED`.
- **Clear-after normal readings at the head** → it has *returned to normal*. Acknowledged: it
  **closes** (`RETURNED_TO_NORMAL`). Unacknowledged: it **stays `OPEN`**, marked
  `returned_to_normal_at`, until somebody acknowledges it — a breach nobody saw must not
  disappear on its own. Acknowledging it then closes it.
- **A breach after that** → the mark is cleared (`REACTIVATED`); still the same alarm.
- **Nothing left behind it** — every breaching reading's round voided → `CLOSED`,
  `EVIDENCE_WITHDRAWN`. An alarm that loses *some* of its readings stays open: it is only
  abandoned when nothing at all supports it.

### The watermark: a closed alarm's readings are spent

When a supervisor closes an alarm whose parameter is still out of range, the next evaluation would
find the same breaching readings and raise it straight back. So a closed alarm's
`last_reading_at` is a watermark for its **(asset, parameter)**: only readings **after** it can
raise a new one — closing a `WARNING × 3` alarm means three *new* warnings, even if the parameter
has since moved under another rule. The exception is `EVIDENCE_WITHDRAWN`: those readings were
taken back, not used, so restoring the voided round may raise an alarm again.

### Voiding a round, and the three things it does not reach

A supervisor voids a round that recorded a warning or a danger. The readings leave the history
(only `SUBMITTED` and `APPROVED` count), the evaluation runs again, and each affected
(asset, parameter) is recomputed from what is left — §4 above says what that produces: the alarm
is **closed** (`EVIDENCE_WITHDRAWN`) when nothing supports it, **kept and shrunk** when other
rounds still breach, and a void whose own reading was *normal* can even **raise** one, because
taking a normal reading out of the middle joins two runs.

**Whether the void happens in the same minute or two days later changes nothing about where the
round sits.** The history is ordered by `completed_at` — device time — so a round finished on
Thursday is judged in Thursday's place however late it is retracted, and nothing is incremented,
so a second void, a retry or two processors reach the same answer. What a delay changes is only
what *else* arrived meanwhile: more breaching rounds keep the alarm open, and a supervisor may
have acted on it already — which is the first of the three things below.

| Not reached | Why, and what it means |
|---|---|
| **An alarm already `CLOSED`** | `findLiveForAssets` returns `state <> CLOSED`, so a closed alarm is never reconciled. A void two days later does not reopen it, does not withdraw it, and **does not release its watermark**: it was closed `RESOLVED` or `RETURNED_TO_NORMAL`, not `EVIDENCE_WITHDRAWN`, so those readings stay *spent* even though their round is now void. A new alarm on that parameter needs readings **after** them. |
| **A webhook already delivered** | Only `ALARM_RAISED` and `ALARM_ESCALATED` are sent (§7). A withdrawal sends nothing, so an SMS or an email the receiving system already raised off that alarm is **not retracted** — the alarm's own page and the queue are the only places the withdrawal shows. |
| **The breach report's timing** | `/reports/exceptions` and the dashboard's تخطی‌های امروز filter on the sheet's status *at query time*, so the row disappears the moment the void commits — while the alarm is recomputed **after** the commit, on the alarm thread. For a few seconds the report and the queue disagree, and the queue is the one catching up. (The entry's own `max_severity` is never rewritten; the filter is on the round.) |

**Restoring is the way back.** `restoreVoided` evaluates again, and because `consumedWatermark`
excludes `EVIDENCE_WITHDRAWN`, an alarm closed by the void **can be raised again** — which is
exactly what would not happen if a supervisor had closed it themselves.

### A parameter that moves between rules

A live alarm keeps its identity (asset, parameter) and the trigger, run and clear-after it was
raised under. Moving its parameter into a specific rule, or out of one, changes the alarms raised
*from then on*; the live one carries on — growing, escalating and clearing by its own frozen terms
— until it closes. Only switching off **the rule that raised it** closes it (`RULE_DISABLED`).

### Reading the severity of one parameter

`breached_fields` lists dangers before warnings **but does not record where they end**. On a round
that recorded a danger *and* a warning, «was `temp` a danger?» cannot be read off the stored
columns. In that case — and for a reading stored before severity was — the evaluator's own
function (`EntrySeverityEvaluator.fieldSeverities`) is asked, with the definitions that round was
judged by. Never a second implementation of the judgement ([AGENTS.md](../AGENTS.md) #189).

### Evaluated twice, late, or out of order

Nothing is incremented: every evaluation recomputes from the stored readings. A duplicated
request, a retry, two processors, a late round — same readings, same answer.

---

## 5. Lifecycle

```
            raise                 acknowledge                 returns to normal
  (none) ─────────▶ OPEN ───────────────────────▶ ACKNOWLEDGED ───────────────────▶ CLOSED
                     │ ▲                               │                     (RETURNED_TO_NORMAL)
                     │ └──────── escalated ────────────┘
                     │
                     ├── close with a reason (RESOLVED) ─────────────────────────▶ CLOSED
                     ├── every reading behind it voided (EVIDENCE_WITHDRAWN) ────▶ CLOSED
                     └── rule switched off (RULE_DISABLED) ──────────────────────▶ CLOSED
```

| Decision | Who | Rule |
|---|---|---|
| **Acknowledge** («دیدم — در حال پیگیری») | holders of `POST:/alarms/{id}/acknowledge` in whose scope it is | only an `OPEN` alarm; optional note; closes it if it already returned to normal |
| **Close** («تعیین تکلیف») | holders of `POST:/alarms/{id}/close` in whose scope it is | a live alarm; **a reason in words is required** |

Both are optimistically locked (`alarms.version`): a supervisor's decision colliding with an
evaluation in the same instant fails cleanly with «دوباره نگاه کنید», and the evaluation is
retried from its queue.

Every change is a line in `alarm_events` — the alarm's own timeline, shown on its page. Alarms are
therefore excluded from the generic `audit_log` (`AuditEntitySupport`); **rules are not**.

---

## 6. Who sees what

An alarm belongs to the unit responsible for the round that raised it. It is visible to exactly
the people who could see that unit's rounds — `getAccessibleUnitIds`, which includes the units
**below** the ones a person supervises — and to every unrestricted reader. An alarm whose round had
no unit is visible only to unrestricted readers. The same rule decides who may acknowledge or
close.

**On the landing dashboard** (`/`), a holder of `GET:/alarms` sees three counts — **تعیین تکلیف
نشده**, **در حال پیگیری**, **باز در محدوده خطر** — each a link to the queue already filtered to what
it counts. Counts only: the list, with its buttons, is one click away. A supervisor lands on `/`
after login (V16), so this is the first thing they see. Not bound to the dashboard's period: an
alarm raised last month and never looked at is the one it exists for ([reports.md](reports.md) §0).

`/alarms` is the queue: unacknowledged first, then priority, then danger before warning, then the
newest reading.

---

## 7. The webhook

### Receivers are registered in the panel (V21)

`/alarm-webhooks` («وب‌هوک‌های آلارم»). An administrator registers **as many receivers as the
plant has** — an SMS gateway, a CMMS, the safety officer's system — and each one has:

| Setting | Meaning |
|---|---|
| name | how the delivery log names it; unique, case-insensitively |
| address | absolute `http(s)`, no user name or password in it; checked at save **and before every request** (below) |
| API key (optional) | sent as `X-API-Key` — the V17 contract, unchanged. Never shown again after it is saved |
| signing secret | **generated**, shown once, regenerated on demand; the request carries an HMAC of it (below), never the secret |
| events | `ALARM_RAISED`, `ALARM_ESCALATED`, or both (nothing ticked = every event, including ones added later) |
| which alarms | **همه آلارم‌ها** (every alarm, including rules added later) or **فقط قواعد انتخاب‌شده** — a list of rules, where the default rule is one of the options |
| phone numbers | whether this receiver's payloads carry supervisors' `phoneNumber` — personal data leaving the plant, so per receiver |
| active | an inactive receiver is queued nothing new; what was already queued for it **waits, unattempted**, and goes out when it is switched back on |

**The requirement it answers:** every alarm to the general receiver, and one sensitive
parameter's alarm *also* to a second one. Register receiver A with «همه آلارم‌ها» and receiver B
with «فقط قواعد انتخاب‌شده» listing that parameter's rule: an alarm of that rule becomes **two
deliveries** with one `X-Event-Id`, each retried and failing on its own, and an alarm of any other
rule reaches A only. A rule a receiver lists **cannot be deleted** until the receiver lets go of it
— a receiver whose only rule vanished would go silent with nothing to say so; a rule with history
is deactivated rather than deleted anyway.

**No receiver, or none for an event:** alarms are raised and shown, nothing is sent, **and nothing
is queued** — adding a receiver later does not replay a month of alarms handled in the panel long
ago.

### Where a receiver may point — the guard that replaced «configuration, never data»

V17 kept the address in configuration because an outbound URL an administrator can type is a way
to point the server at an internal service (SSRF). The panel now holds it, and the guard is
`AlarmWebhookTargetPolicy`:

- **Never a receiver:** loopback (`127.0.0.0/8`, `::1`), link-local (`169.254.0.0/16` — the cloud
  metadata address — and `fe80::/10`), unspecified (`0.0.0.0`, `::`) and multicast — judged on
  **what the name resolves to**, so a public name pointed at `127.0.0.1` is refused like the address
  itself. Private ranges (`10/8`, `192.168/16`, …) are allowed: that is where a plant's receivers
  are.
- **`app.alarm.webhook.allowed-hosts`** (`APP_ALARM_WEBHOOK_ALLOWED_HOSTS`), when set, is the
  **complete** list of hosts — exact names, `*.suffix` wildcards or addresses. **Set it in
  production** ([deployment.md](deployment.md)): «where may alarms go» stays under the control of
  whoever runs the server, and the day-to-day choice stays in the panel. A host listed **exactly**
  skips the address check, so a receiver on the same machine can be allowed deliberately; a
  wildcard never opens loopback.
- **Checked at save and again before every request**, on the addresses the name resolves to then
  (DNS rebinding). A refused attempt is recorded as a failed attempt with the rule it broke.
- **Nothing a receiver answers is kept but its status code.** The delivery log is readable in the
  same page, and echoing a response body would let whoever can type an address read whatever
  answers at it. Redirects are never followed.

```properties
app.alarm.webhook.allowed-hosts=${APP_ALARM_WEBHOOK_ALLOWED_HOSTS:}   # production: set it
app.alarm.webhook.connect-timeout-ms=5000
app.alarm.webhook.request-timeout-ms=10000
app.alarm.webhook.max-attempts=10
app.alarm.webhook.poll-ms=15000
# Deprecated — read once, by AlarmWebhookLegacyImportRunner (docs/jobs.md):
app.alarm.webhook.url / api-key / include-contact-details
```

**Upgrading from V17/V18.** A deployment that had `app.alarm.webhook.url` set gets it imported
**once**, on the first start after V21, as a receiver named «وب‌هوک پیکربندی سرور» with the
configured key and contact switch, every event and every alarm, and a freshly generated signing
secret; deliveries still waiting are attached to it, so nothing queued is lost. It records that it
ran (`app_settings`: `alarm.webhook.legacy_imported`), so deleting the imported receiver is final;
afterwards the old settings do nothing and the log says so while they are still set. If the old
address was on the same machine, add its host to `allowed-hosts` exactly, or every request to it
is refused (the import warns).

### Events

| `X-Event-Type` | When |
|---|---|
| `ALARM_RAISED` | an alarm was created |
| `ALARM_ESCALATED` | a live alarm's severity rose (warning → danger) |
| `WEBHOOK_TEST` | «ارسال آزمایشی» on the webhooks page — to that receiver only, no alarm behind it; answer it with any 2xx |

Acknowledgements, closures and returns to normal are not sent.

### The request

```
POST {the receiver's address}
Content-Type:        application/json; charset=utf-8
X-API-Key:           {the receiver's API key}       (omitted when it has none)
X-Event-Id:          7f3c9a2e-…                      (deduplicate on this)
X-Event-Type:        ALARM_RAISED
X-Webhook-Timestamp: 1794590164                      (Unix seconds of this attempt)
X-Webhook-Signature: sha256=5f0c…                    (hex HMAC-SHA256, below)
User-Agent:          logsheet-alarm-webhook/2
```

**The signature** is `HMAC-SHA256(signing secret, timestamp + "." + body)` in lower-case hex, over
the **exact bytes** of the body, computed afresh for every attempt (`AlarmWebhookDispatcher.signature`).
It proves the body came from this server unaltered; with the timestamp inside it, a receiver that
refuses a timestamp more than a few minutes old is also safe from a captured request replayed
later. The secret itself is never sent. This is the scheme GitHub and Stripe use, so most receiver
frameworks verify it in a few lines.

```json
{
  "eventId": "7f3c9a2e-5b1d-4c0a-9e3f-2a6b8d4c1e90",
  "eventType": "ALARM_RAISED",
  "occurredAt": "2026-11-10T08:15:02.113Z",
  "alarm": {
    "id": 41, "state": "OPEN", "severity": "DANGER", "priority": "HIGH",
    "triggerSeverity": "WARNING", "consecutiveReadings": 3, "occurrenceCount": 3,
    "conditionMetAt": "2026-11-10T07:58:40.000Z",
    "firstReadingAt": "2026-11-08T07:51:10.000Z",
    "lastReadingAt": "2026-11-10T07:58:40.000Z",
    "lastValue": "120", "expectedAction": "توقف پمپ و اعزام مکانیک",
    "raisedAt": "2026-11-10T08:15:02.090Z",
    "rule": { "id": 4, "name": "قاعده عمومی — همه پارامترها", "scope": "ALL_FIELDS" },
    "panelPath": "/alarms/41"
  },
  "field": { "definitionId": 7, "key": "temp", "label": "دما", "unit": "°C" },
  "asset": { "id": 1042, "code": "P-101", "name": "پمپ تزریق", "className": "پمپ", "nfcTagId": "PK-0102-P2" },
  "logSheet": {
    "id": 9001, "templateId": 3, "templateName": "راند پمپ‌های واحد ۱", "status": "SUBMITTED",
    "completedAt": "2026-11-10T07:58:40.000Z",
    "completedBy": { "username": "a.saljooghi", "fullName": "احمد سلجوقی", "personnelCode": "4996" }
  },
  "readings": [
    { "logSheetId": 9001, "readAt": "2026-11-10T07:58:40.000Z", "value": "120", "severity": "DANGER" },
    { "logSheetId": 8990, "readAt": "2026-11-09T07:55:12.000Z", "value": "86",  "severity": "WARNING" },
    { "logSheetId": 8975, "readAt": "2026-11-08T07:51:10.000Z", "value": "85",  "severity": "WARNING" }
  ],
  "operationalUnits": [
    { "id": 3, "code": "OP-01", "name": "واحد عملیاتی ۱", "parentId": 1, "relation": "RESPONSIBLE",
      "supervisors": [ { "username": "m.karimi", "fullName": "محمد کریمی", "personnelCode": "3120", "phoneNumber": "09120000000" } ] },
    { "id": 1, "code": "OP", "name": "بهره‌برداری", "parentId": null, "relation": "PARENT_OF_RESPONSIBLE",
      "supervisors": [ { "username": "r.ahmadi", "fullName": "رضا احمدی", "personnelCode": "2001", "phoneNumber": "09120000001" } ] },
    { "id": 5, "code": "MAINT", "name": "نگهداری", "parentId": null, "relation": "ALSO_COVERS_ASSET",
      "supervisors": [] }
  ]
}
```

| Part | Notes |
|---|---|
| `alarm.lastValue`, `readings[].value` | the stored value as text; numbers without a trailing `.0`; a multiselect joined with «، » |
| `readings` | the breaching readings behind the alarm, newest first, at most 20 |
| `operationalUnits[].relation` | `RESPONSIBLE` — the unit whose round raised it (its supervisors see it in the panel). `PARENT_OF_RESPONSIBLE` — each ancestor (they see it too). `ALSO_COVERS_ASSET` — any other unit whose rounds have covered this asset (they do not see it, but read the same machine). An asset belongs to no unit in this system; responsibility runs through the log sheet ([hierarchy.md](hierarchy.md) §5) |
| `supervisors` | the unit's **own**, **active** supervisors. `phoneNumber` only when the receiver's «شماره تلفن سرپرستان» is on — two receivers can get two bodies for one event. There is no email column on a user; a receiver that emails maps `personnelCode` itself |
| `panelPath` | relative — this server does not know the address it is reached by |
| internal user ids | never present; `personnelCode` is the join key |

**The body is built when the event happens and stored verbatim.** A retry an hour later sends the
same bytes — the supervisors of the unit *then*, the reading *then* — rather than a new
description.

### Delivery

- **One delivery per receiver.** An event that reaches three receivers is three rows sharing one
  `eventId`; one receiver down never holds back another.
- **2xx** is delivered. Anything else — another status, a refused connection, a timeout, an
  address the target policy refuses — is a failed attempt: retried after 30 s, 1 min, 2 min …
  capped at an hour, until `max-attempts`; then `FAILED`, visible on `/alarm-webhooks` with its
  last error (a status code, never the receiver's body), and resent only when an administrator
  presses «ارسال دوباره».
- **Deleting a receiver** fails what was still waiting for it («Endpoint deleted before
  delivery.») and keeps every row under the receiver's frozen name; such a row cannot be resent.
- **At-least-once.** A process killed between the receiver's 2xx and the «delivered» write sends
  the event again when its lease expires. **The receiver must deduplicate on `X-Event-Id`.**
- **No call is made inside a transaction**; redirects are not followed; both timeouts are always
  set. A slow receiver holds no database connection and no row lock.
- The API key and the signing secret are never logged, never stored in a delivery row, masked in
  `audit_log`, and never shown in the panel after they are saved.

### Writing a receiver

1. Verify `X-Webhook-Signature` against the signing secret you were given (and refuse a
   timestamp older than about five minutes); or, at least, check `X-API-Key`. Reject anything
   else with 401.
2. Deduplicate on `X-Event-Id` (store the ids you have handled, for at least a day).
3. Answer **2xx quickly**, then do the slow work (SMS, email) on your side. A receiver that sends
   its SMS before answering, and takes longer than `request-timeout-ms`, will be retried and will
   send the SMS twice.
4. Pick recipients by `relation`: `RESPONSIBLE` first; `PARENT_OF_RESPONSIBLE` for escalation.

---

## 8. Limits and things deliberately not done

- **Sheet-level questions do not raise alarms.** Severity is not evaluated on round-level answers
  (`sheet_form_data`); a rule can only name a class field.
- **Asset-private fields** (a per-asset full override, roadmap §20) have no field definition and so
  no rule.
- **History depth.** Raising looks at the newest 200 readings after the watermark; a live alarm
  recounts at most its newest 1000. An alarm open for years on a daily round stops counting its
  oldest readings, and is never withdrawn on a truncated history.
- **No follow-up form** is created from an alarm: turning one into work is a person's decision, and not built.
- **No email or SMS** from this server: it has no SMTP or SMS dependency, and on a plant LAN the
  receiver is the right place for both.
