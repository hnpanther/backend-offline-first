# Read this first

Spring Boot 4.1 / Java 25 / PostgreSQL backend for an offline-first industrial log-sheet
system. Persian (RTL) throughout. Its companion PWA lives at
`H:\Project\offline-first-project\offline-first-pwa`.

## Before you change anything

Read, in this order:

1. **[AGENTS.md](AGENTS.md)** — the conventions of this codebase and a numbered list of traps
   found the hard way. Several are not guessable from the code, and each one cost a live bug:
   Thymeleaf treating the string `"off"` as boolean false; `th:replace` silently discarding
   anything outside `#pageContent`; SpEL refusing a null map index; `visibleUnitIds()`
   returning `null` for an unrestricted admin.
2. **[README.md](README.md)** — what the system does, how to run it, and every feature end to
   end.
3. **[docs/](docs/)** — the deep references:

| File | When you need it |
|---|---|
| [docs/architecture.md](docs/architecture.md) | **The map.** Four tiers, what each is for, how a round flows end to end, and the identifiers that cross the boundary. Start here if the system is new to you; every other file below zooms into one part of it. |
| [docs/schema.md](docs/schema.md) | Every table, column, index and constraint, with the reasoning. **The current shape of the schema, not a replay of migrations.** |
| [docs/hierarchy.md](docs/hierarchy.md) | Location → Asset, and access scope. **Required reading before touching placement or scope.** |
| [docs/security.md](docs/security.md) | Roles, permissions, the three enforcement layers, and **which rules key off a role's code rather than its permissions** (so a duplicated role does not inherit them). §3c is **device enrolment** — which handheld may use the mobile app, off by default (`V13`). Read before adding an endpoint or a custom role. |
| [docs/log-sheets.md](docs/log-sheets.md) | The core business object: lifecycle, states, endpoints, status requests |
| [docs/jobs.md](docs/jobs.md) | Schedulers, startup runners, async pools, and how each fails |
| [docs/reports.md](docs/reports.md) | Every report and the exact formula behind each number |
| [docs/deployment.md](docs/deployment.md) | **Running it as a service.** Three ways — systemd (Linux), WinSW (Windows), and **Docker** (everything under [`deploy/`](deploy/), built from a jar rather than inside the image; the complete Ubuntu runbook and the containerised backup job are both in there) — plus where the secrets go, PostgreSQL, backups, and the failures that look like something else |
| [docs/performance.md](docs/performance.md) | **What was measured, and what is deliberately left.** How to count SQL per request and time a page, the numbers for every list page and the API, the N+1 that was fixed, and the one that is knowingly still there with the conditions that would make it urgent. |
| [docs/roadmap.md](docs/roadmap.md) | **Decisions deferred, with the facts they rest on.** Three kinds of entry — features that do not exist; behaviour that does exist with a change deliberately not made; and a proposal that was weighed and **declined**, kept so it is not re-argued from scratch (§14, splitting this into services). Sub-assets; request rate limiting; the per-tick cost of the mobile inbox; general optimistic concurrency; an operator removed from a unit while offline — each with the conditions that would make it urgent. (Restoring archived readings used to live here; it is built, and documented in the PWA's `docs/sync.md`.) Its «what happens today» passages are true and are mirrored in the reference files; what is unbuilt is the change. Read a section's own framing before acting on it. (The third-party integration API used to live here; it is built, and now documented in `security.md` §7, `log-sheets.md` §7, `schema.md` and the README.) §18 is the read-only review of 2026-10-15: fourteen findings, **nine of them fixed** (18a, 18c, 18d, 18e, 18f, 18g, 18h, 18i, 18l), the other five open each for a stated reason — plus an explicit **priority for a production deployment** that names the two things outside it that matter more than any of them. §20 is the **per-asset parameter and band** design — class → sub-class → asset — with the measurements from the operational database that chose it over a private class per asset (507 distinct pump names for 1,555 pumps; 47 entries and 22 types per sheet; 253 bytes a field definition). §21 is **converting a scoped template to an explicit one**. §22 is **forms that are not a log sheet** — a question belonging to the round rather than to an asset, a scheduled security patrol (which needs almost nothing: the NFC tag belongs to the position, so the points are sub-functions and the round is an ordinary template), and an ad-hoc violation report; it also records that `asset_classes` + `field_definitions` **is already the form builder**, and that the real obstacles are attachments being log-sheet-bound and an access rule where the reporter need not belong to the responsible unit. Its central instruction is to build the sheet-level questions **generally** — owned by a custom sheet as well as a template, and with the assets optional — because a questions-only log sheet then inherits scheduling, assignment, offline sync, attachments and reporting, which collapses the patrol and most of the ad-hoc report into it; the zero-asset case is built: it turned out to need a recorded mode rather than a relaxed guard, because an empty asset list already meant «the scope lost its last asset» and the generator is right to refuse that. §23 is **disabling a field instead of deleting it — built**; it is kept for the two decisions that will otherwise be re-opened: the key index must stay **non-partial** (a partial one allows two definitions of one key and a stored reading then belongs to neither, so a key stops being an identity), and the hard delete stays but is refused once anything names the key. §5, *a reading whose field no longer exists*, is the matching **mostly-closed** entry — two of its three options are now built, and what is left is labelling a reading from its own sheet's snapshot rather than from today's definition. §24 is **sheet-level fields — every phase built** (`V9`: the editor, the freeze, the answers, their own save button, their revisions, and the tablet — one additive field each way, no Dexie bump. `V10`: the asset-less round as a third `AssetSelectionMode`, **media on a round-level question** (`attachments.asset_id` nullable, one `MediaOwner` record carrying both owners), a **custom sheet's own questions** through the same editor page with four rules and a freeze at the first answer, the `sheetForm` component on the integration record, and `required` — judged on the round-level save button **and nowhere else**, which removes the rollout constraint instead of managing it. The behaviour is in `log-sheets.md`, `schema.md` and `security.md`), plus ten findings from the code that §22a did not name: there is no way to save an asset-less template today (it needs a third `AssetSelectionMode`, not one relaxed guard), `complete()` is cosmetic and nothing in the completion path counts assets, the tablet refuses to submit a zero-entry sheet and blames the network, `fd_<entryId>_<key>` cannot carry a sheet-level field, and `validateFilledEntry` would make a required sheet question never fire. It also covers the sheet-level form's **own save button** (reusing the complete authority and `log-sheet-fill.js`'s existing HTML-fragment POST) and **revisions of those answers** — a sibling `log_sheet_revisions` table rather than a nullable FK, because the renderer is already owner-agnostic, plus the two attribution columns the sheet does not have today (`draft_saved_by_user_id` moves on every per-asset save, so reusing it would credit the wrong person). §25 is **QR beside NFC — built** (`V11`): `AssetScanMode` (`NFC` / `QR` / `NFC_OR_QR` / **`NONE`**, default `NFC`) on the template and **frozen onto the sheet**, so a template changed later cannot change a round already being walked; a round with no assets is forced to `NONE`. The one fact that decided it is that a QR code has no hardware serial, so `nfcStrictSerialMatch` does not apply to a QR read; the payload is the asset's own `nfc_tag_id`, so nothing gained a second identifier. `entry_source` gained `PWA_QR` and the mobile contract gained an additive `entrySource` field, because a boolean cannot tell a QR scan from a tag scan. The CHECK on `log_sheet_entry_revisions` that would have failed on the first *correction* of a QR reading is rebuilt in V11; the device half is the PWA's §12, which uses `BarcodeDetector` with no bundled decoder. §27 is **withdrawing an approval, admin only by default — built** (`V8`): the behaviour is in `security.md`, `log-sheets.md` and AGENTS.md #161, and what is left in the roadmap is the declined self-withdrawal window plus what the change actually cost — "one migration and no Java" was wrong twice, and one of those was a dialog promising the opposite of the new rule. §26 is **filtering the log-sheet list — the date half built**: the responsible unit leads the list with محدوده and زمان تکمیل beside it (every column hideable through the picker that already existed), a half-open range over a **named** date column that is refused rather than defaulted when unknown, and one `LogSheetListFilter` shared by the page and its Excel export so the file cannot disagree with the screen; what is left is the unit / template / origin / assignee / overdue / breach filters and the sort. §19 is what it would take to run **more than one instance** (Kubernetes or otherwise): the import subsystem that turns destructive under N pods, three stores on local disk, the per-JVM state, which scheduled jobs are already safe and which are not, and the two-Deployment shape that removes most of the work. §30 is **device enrolment — built** (`V13`): a non-extractable key per device, a one-time code an approver issues for one request number, enforced at login only and inert when off; kept for the decisions that will otherwise be re-opened (two layers that never touch, no admin password typed on a tablet, the key in its own IndexedDB database) and for what is left (Android Keystore, one session per device, MDM). §31 is **a choice field you can search, and type into when the answer is not listed** — not built, in two phases: phase 1 needs no migration (search on the existing `select`/`multiselect` above ~8 options, and free entry only by an explicit `validation.allowCustom` that must go through `FieldValidationSupport.build` and the separately-named marker of AGENTS.md #171, with the server still the boundary); phase 2 is **lookup lists** (a migration of its own) for thousands of entries such as personnel codes, because options live in a definition frozen into every sheet and sent in every bundle. §32 is **a signature field** — not built: a new `signature` type carried by the attachment pipeline, bound to the signer, the time and a hash of the answers it covers. §33 is **a fingerprint field — declined**: a web app cannot read one, WebAuthn proves only «someone enrolled on this device», and stored biometrics are the wrong liability. **§34 — a voided sync dropped the round's answers — is built (`V14`)**: `voidSubmission` kept `dto.getEntries()` only, so every takeover, cancellation or double completion of a round with questions discarded the answers the tablet sent (found on sheet 42); `log_sheet_void_submissions.sheet_form_data` now keeps them, the review page shows them, and attachment cleanup protects the media they name. The behaviour is in `log-sheets.md`, `schema.md` and AGENTS.md #180; answers voided before V14 are not recoverable from the server. |

## The rule that keeps this useful

**Any change to behaviour, schema, jobs, endpoints or reports must update the matching
document in the same commit.** Not afterwards, not in a follow-up.

Documentation that lags the code is worse than none: it is confidently wrong, and the next
person — or the next agent — will trust it. A wrong documented formula produces a wrong
decision about a plant.

Concretely:

| You change | Also update |
|---|---|
| A migration / a table / an index | [docs/schema.md](docs/schema.md) |
| Placement, ancestry, or a scope CTE | [docs/hierarchy.md](docs/hierarchy.md) |
| A permission, a role grant, or an access rule | [docs/security.md](docs/security.md) |
| A log sheet state, action or endpoint | [docs/log-sheets.md](docs/log-sheets.md) |
| A scheduler, runner or executor | [docs/jobs.md](docs/jobs.md) |
| How it is installed, started or backed up | [docs/deployment.md](docs/deployment.md) |
| A report or a KPI formula | [docs/reports.md](docs/reports.md) |
| A query count, a page that got slower, or a measurement | [docs/performance.md](docs/performance.md) |
| A trap you only found by debugging | **[AGENTS.md](AGENTS.md)** — add a numbered entry with the *why* |
| How it is containerised — an image, a compose file, a volume, a port | [docs/deployment.md](docs/deployment.md) **and** [deploy/README.md](deploy/README.md) |
| A user-visible feature | [README.md](README.md) |
| A tier, a filter chain, or how the pieces connect | [docs/architecture.md](docs/architecture.md) |
| Anything touching the mobile contract | the PWA's `README.md`, `AGENTS.md` and `docs/` too |

## Two standing constraints

- **A migration that has reached production is closed; one that has not is still editable.**
  Editing an applied one breaks its Flyway checksum and the application refuses to boot. The
  tree now holds **V1–V14**; **which of them a given installation has actually run is
  `flyway_schema_history`'s answer, not this file's** — check there before treating any of them
  as still editable, and when in doubt treat it as closed. **The next *new* change is
  `V15__*.sql`.** Folding several development migrations into one is only for files nothing has
  run yet — an operational database should advance by one version, not three, for changes it
  has never seen — and consolidating an already-applied one means a hand repair of
  `flyway_schema_history`; the procedure, and the `target/classes` step everyone forgets, is
  AGENTS.md gotcha #86. Each migration's purpose is the table at the top of
  [docs/schema.md](docs/schema.md).
- **After a manual local run, kill the port-8081 java process.**

## Verifying

```bash
mvn -o test
```

`ddl-auto=validate` is set, so a successful boot is itself proof that every entity matches the
schema. Live runs have repeatedly found defects that a fully green suite did not — two features
completely dead due to template placement, a status value invisible due to Thymeleaf
truthiness, a report hiding 46 uninspected assets. Run it live before claiming a UI change works.

**One suite is not in `mvn test`** — the browser-side tests for `ui-preferences.js`, the script
that applies stored display preferences before the first paint:

```bash
node --test src/test/js/ui-preferences.test.cjs
```

It uses only `node:test` and `node:vm` — no `package.json`, no install, nothing fetched — but it
does need Node on the machine, and Node is not otherwise a dependency of this project. Wiring it
into Maven would make `mvn test` fail wherever Node is absent, which is the wrong trade for a
plant build, so it is run separately instead. **Run it when you touch `ui-preferences.js`**: it
covers the parts that are easy to get wrong and impossible to see — unreadable storage, corrupt
JSON, and the guard that stops a crafted storage key from injecting CSS into the page.

(Pass the file, not the directory: `--test` on a directory resolves the path differently on
Windows and reports a module-not-found error rather than running anything.)
