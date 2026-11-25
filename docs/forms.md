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
