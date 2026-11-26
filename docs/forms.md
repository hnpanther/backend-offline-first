# Ad-hoc form field behaviour

## Reference labels and parent filtering

The panel and PWA display a title followed by its code in an isolated left-to-right
parenthesis (`<bdi>`). Plain-text PWA values use `title · کد: code`, so a numeric title
cannot be confused with its code. No database id is added to the answer label.

In `/forms/{id}/fields`, a lookup or lookupmulti field can enable **filter by parent**.
The selected reference list must have a parent list. The editor disables this checkbox
for a list without a parent; the server independently rejects an invalid configuration.
The field stores `lookupFilterByParent: true` and the resolved parent list code as
`lookupParentList` in its validation JSON. With the checkbox off, selection is independent.

Example: the Units reference list has Managers as its parent. A form contains a Managers
field and a Units field with filtering enabled. Selecting M1 offers only children of M1;
changing or clearing Managers clears the dependent Units answer and invalidates any pending
search. The initial display of a saved answer does not clear it. A child waits for a parent
selection when a matching parent field exists. If no matching parent field exists in the
same form, it works independently. Multiple parent fields or multi-select answers contribute
the union of their selected codes. Filtering does not implicitly make the parent required.

Panel searches pass repeated `parent` query parameters to the existing new-form and draft
lookup endpoints. Filtering precedes the result limit in the database. PWA search filters
its leased IndexedDB copy before applying the result limit; no network is needed to select
an item. Device lookup pages include `parentCode` (resolved in one batched parent query per
page, not one query per item). Existing grants, offline-column restrictions and leases apply.

This is an **option filter, not an authorization or referential-integrity boundary**. The
existing policy still preserves unknown/retired codes and stamps their verdict on the server.
Explicit unlisted-code entry and code-only fallback keep that policy. Unit access is enforced
separately. Definitions and published snapshots freeze the parent list code: after changing
a reference list's parent, save the field definition again for new submissions; existing
submission snapshots keep their original definition.

## Date and time limits

Ad-hoc datetime fields expose two optional constraints:

- `dateTimeRange: { min?, max? }`: inclusive fixed UTC epoch-millisecond limits, edited with
  the local Jalali picker in Tehran time. Either side can be omitted.
- `dateTimeRelative: { beforeMinutes?, afterMinutes? }`: inclusive bounds around recording
  time. Minutes are integers from 0 to 5,256,000. Blank means unbounded; zero is a real bound.

When both are configured, the value must satisfy both. The editor rejects inverted dates,
invalid dates, negative minutes and excessive minutes. Other field types do not retain these
constraints. Existing definitions without these keys remain unrestricted. Requiredness is
unchanged: an optional empty date can be saved, but an answered date outside its window is
refused in both draft and final paths. The UI gives Persian feedback and the service validates
again against the server-owned frozen schema.

For **mobile forms**, recording time is the `deviceTimeAt` captured when the user saves or
finalizes. The PWA uses its existing `serverNow()` clock (server offset when available), captures
it once for validation and persistence, and reuses it on delivery/retry. A form recorded at
10:00 with a date of 09:58 and a five-minute lookback remains valid when synced at 14:00.
A later deliberate edit/finalization is a new recording time and revalidates the answers.
Panel writes use server time. Legacy mobile requests without deviceTimeAt use server time.

The client-provided time is a business rule reference, **not proof of when work occurred**.
It does not control access, lifecycle, server timestamps or audit attribution. An arbitrary
JSON client can claim another device time; preventing that would conflict with accepting
unattested offline recording time and needs a separate trusted-device design.

## Filing-unit enforcement

FormAccessService checks the form's eligible filing units and the actor's current unit scope
on draft creation/update, finalization of an existing draft, and create-on-submit. A plant-wide
capability intentionally permits broader caller scope, but never bypasses form eligibility.
A supplied subjectUnitId does not grant access: the server mirrors reporterUnitId to subjectUnitId.
Invalid scope is refused before mutating the managed filing-unit fields. FormReviewIntegrationTest
covers deserialized crafted JSON with a foreign unit on draft creation, existing-draft finalization
and create-on-submit, and verifies that a refused finalization keeps the original filing unit.

## Delivery and verification

Deploy the backend before the PWA. On first sync after upgrading an old lookup cache, the PWA
replays items from cursor zero to populate parentCode. An interrupted upgrade remains incomplete
and resumes from its committed cursor; partial metadata must not be advertised as a complete list.
No new dependency, external UI asset, database column or Flyway migration is needed for this change.
Validation keys travel in the existing JSONB definition snapshots and form schema versions.
Existing drafts keep their pinned schema; changing the editor does not retrofit them.

Regression coverage includes editor POST/render, database child filtering, device parent-code
payloads, date boundaries and rejected windows, delayed mobile delivery, filing-unit spoofing,
cache-upgrade resume, local recording-time preservation, and panel JavaScript behaviour.
Build/test results and verification limits are recorded in [issue.md](issue.md).

## Offline form recovery (F-02 and F-05)

A new PWA filing normally carries the server-issued `schemaVersionId`. When a stored draft
predates version IDs, the updated PWA sends `legacyFields` from the draft's own frozen fields
on draft upsert and final submission. The server compares the complete definition, including
field identity, label, units, validation, order and version. An existing filing uses its frozen
snapshot. A first filing must match the current or a published definition of that same form.
The server stores its own matching definition. Missing or unverifiable evidence on first mobile
delivery produces a validation refusal. For example, a severity of 3 recorded on a five-point
scale cannot silently become 3 on a ten-point scale. If the old definition was never published
and has changed, open a new form and review the original answers manually. The original remains
available on the device. Older apps without either version or evidence must upgrade for first delivery.

The updated PWA requests `requireUploadedMedia: true` on final submission. The server checks
each referenced image, recording, video and signature against stored uploads for the same
filing, field and media kind before changing the filing to SUBMITTED. Existing API clients
that omit this flag retain the late-upload contract. Deploy the backend before the PWA.
An upload rejected as permanently invalid leaves the filing available for correction.
Transient upload failures stay queued. Saving an explicit replacement records the answer and
queues removal of the previous file in the same IndexedDB transaction. A file reference can
survive a reload even when its bytes are only on the server.

The form queue sends `DELETE /api/attachments/forms/{submissionId}/{id}?fieldKey=...` before
uploading the replacement. It reuses the existing attachment-delete permission and draft edit
scope. Missing files or a mismatched form or field are idempotent no-ops. Unreferenced local
captures can be selected explicitly. Accepted finals follow the existing retention policy.
A final awaiting correction after VALIDATION_ERROR stays on the device until the operator acts.

## Date clearing, validation and last answer saver

The Jalali date/time control offers a clear action for a populated editable value. It writes
the existing null representation. Drafts may still be saved with required fields empty.
A blocked save shows the summary beside the action buttons and marks the affected fields.
The operator can correct the values in place. Date ranges are checked against recording time.

Successful draft and final answer saves record the authenticated saver separately from the
original reporter and approver. The panel detail header displays the saved name. The PWA stores
the local saver with an offline edit and accepts the server's name with an imported filing.
Review, void and reopen operations preserve the last answer saver. V35 recovers historical
savers from submit actions and edits that superseded draft answers. Older records without an
attributable save display an unknown saver in the panel.

## Concurrent edits and incremental form history (V36–V37)

The panel and tablet send the last acknowledged server `updatedAt` as `expectedUpdatedAt`.
Zero identifies a first delivery. The server locks the filing, checks that version and stamps
the next accepted edit or review with a strictly increasing value. A retry of the same draft
save can recover its acknowledgement. At a conflict, the tablet keeps the operator's answers
and pauses delivery, attachment removal and retention for that filing. After comparing the
current server draft, the operator can explicitly save their answers against its version.
A concurrent change during that comparison produces another conflict. A completed server
filing remains visible for comparison and requires a supervisor to reopen it before editing.
Older clients that omit the version retain their old draft-write contract.

Each accepted final records its action key on the immutable SUBMIT entry. A retry from its
actor for the same filing returns the existing result in that cycle. Reopen preserves the
receipt and starts a new cycle. Replaying the old action returns a conflict, including after
another final. Keys erased by reopen before V36 cannot be reconstructed. A mobile final
without a version after reopen requires review on an updated client. A legacy key with no
attributable final actor stays reserved.

`GET /api/form-submissions/changes` uses the existing own-history authority. It returns
`ownerId`, `items`, `next`, `through` and `hasMore`. The default page size is 50 and the
maximum is 100. The first page fixes the upper bound for the pass. Send `after=next` and
the same `through` on subsequent pages. Once the pass ends, omit `through` on the next
poll. Drafts pass every bootstrap age filter. A fresh tablet requests terminal rows only
within the delivered retention window. An upgraded tablet with previously mirrored open
drafts runs one paged reconciliation of its old cache. Later polls request only changes.
A terminal row that aged out locally is skipped on import, including after a later review.

Each IndexedDB page merge and its per-user checkpoint commit together. Interruptions resume
from the last committed page. A different signed-in user cannot apply an in-flight page.
The legacy history route still serves its newest-first array to older tablets. The server
reads and writes it in batches of 50. New PWA installations require the updated backend.
The catalog loads linked units, active fields, latest schema versions and referenced lookup
lists in batches. Only new or changed field definitions need a new schema version.
