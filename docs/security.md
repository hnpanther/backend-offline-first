# Security, Roles and Permissions

Who may do what, where each rule is enforced, and the places where the answer depends on a
role's **code** rather than on its permissions.

> **Authorities are `METHOD:path` strings.** `GET:/locations`,
> `POST:/log-sheets/{id}/complete` — including the literal `{id}` braces. The permission *is*
> the route; there is no mapping layer in between. See [schema.md](schema.md#roles-permissions-role_permissions-user_roles).

---

# 1. The three layers

An authority is necessary but rarely sufficient. Every request passes up to three independent
checks, and they answer different questions:

| Layer | Question | Where |
|---|---|---|
| **Endpoint** | May this role call this route at all? | `@PreAuthorize("hasAuthority('…')")` on the handler |
| **Scope** | May this user act on *this unit*? | `OperationalUnitScopeService`, `AssetAccessService`, `LogSheetAccessService` |
| **Object** | May this user see *this row*? | `requireVisibleLogSheet(id)` and friends |

**Holding the endpoint permission proves nothing about the second and third.** A supervisor
with `GET:/log-sheets/{id}` still cannot open a sheet belonging to a unit they do not
supervise: `LogSheetAccessService.requireVisibleLogSheet` throws `AccessDeniedException` first.

Several service-layer rules are checked in the **service**, not only on the controller,
precisely because a service is reachable from more than one route — see
`AssetStatusRequestService.requireDecider()` for the pattern and its reasoning.

---

# 2. The five system roles

Counts below are the seeded grants, verified against a live database.

| Role | Persian | Scope | admin | api | general | master-data | operational | organization | reports |
|---|---|---|---|---|---|---|---|---|---|
| `ADMIN` | مدیر سیستم | plant-wide | **32** | 15 | 1 | 42 | 24 | 8 | 1 |
| `HIGH_USER` | کاربر ارشد | plant-wide | 3 | 15 | 1 | 42 | 24 | 8 | 1 |
| `SUPERVISOR` | سرپرست | own units + sub-units | — | 14 | — | **1** | 24 | — | 1 |
| `SENIOR_OPERATOR` | اپراتور ارشد | own units only | — | 11 | — | — | 8 | — | — |
| `OPERATOR` | اپراتور | own units only | — | 11 | — | — | 6 | — | — |

`HIGH_USER`'s three `admin` permissions are exactly the batch-import trio
(`GET:/batch-import`, `GET:/batch-import/jobs`, `POST:/batch-import`) — the category is
`admin`, but the capability is not.

`SUPERVISOR`'s single `master-data` permission is **`GET:/log-sheet-templates`** — read only.
See [§5 F2](#f2--supervisors-cannot-create-templates-documentation-was-wrong).

## Unit hierarchy: supervision cascades down, operation does not

> Supervising unit A also covers B, C and everything beneath them — a supervisor is
> responsible for the whole branch. Operating unit A covers **only** A: operators of A and
> operators of B are separate teams that share a manager.

`OperationalUnitScopeService.getSupervisorScopeUnitIds` expands downward, `isOperatorOf` does
not, and `getAccessibleUnitIds` is the union. The unit tree is cycle-guarded because access
control depends on it.

## OPERATOR — the full list

Everything an operator can reach, as seeded:

```
web:  GET:/log-sheets            GET:/log-sheets/{id}       GET:/my-inbox
      GET:/nfc-fault-reports     POST:/log-sheets/{id}/claim
      POST:/log-sheets/{id}/release

api:  GET:/api/bootstrap                     GET:/api/log-sheets/inbox
      GET:/api/log-sheets/{id}/bundle        POST:/api/log-sheets/batch
      POST:/api/log-sheets/{id}/claim        POST:/api/log-sheets/{id}/release
      GET:/api/asset-entries/nfc/{nfcTagId}  POST:/api/nfc-fault-reports/batch
      POST:/api/attachments   GET:/api/attachments/{id}   DELETE:/api/attachments/{id}
```

No web fill/complete — mobile only. `SENIOR_OPERATOR` adds exactly
`GET:/log-sheets/{id}/fill` and `POST:/log-sheets/{id}/complete` to this.

`SUPERVISOR` adds, over `OPERATOR`, on the API side:
`POST:/api/log-sheets/{id}/assign`, `POST:/api/log-sheets/{id}/reassign`,
`GET:/api/operational-units/{unitId}/operators`. The only `api` permission `SUPERVISOR` lacks
that `ADMIN` has is `POST:/api/asset-entries/{id}/nfc-serial` — writing a chip binding is not
an everyday field action.

---

# 3. ⚠️ Access that depends on the role's CODE, not its permissions

**This is the section to read before creating a custom role.**

A number of rules ask "is this user an ADMIN?" by comparing the **role code**, not by checking
a permission. That has a consequence which is easy to miss:

> **Duplicating a role copies its permissions, not its identity.** The "ساخت نقش مشابه" button
> creates a role with a *new code* and every permission of the original. Any rule written
> against the original's code will not recognise the copy. A duplicate of `ADMIN` holds all 123
> permissions and is still not an admin.

The direction of failure is **safe** — a copy is always *more* restricted than its original,
never less — but it is surprising, and it produces bug reports of the form "this role has the
permission and still gets access denied".

## 3a. `isUnitScopedOnly()` — a deny-list

```java
public boolean isUnitScopedOnly() {
    return !hasRole("ADMIN") && !hasRole("HIGH_USER");
}
```

Anything that is not literally `ADMIN` or `HIGH_USER` is treated as unit-scoped. **A custom
role is restricted by default**, which is the correct direction for a default.

Used by: `AssetAccessService.visibleUnitIds`, `LogSheetAccessService` (`canView`,
`findVisibleLogSheets`, `resolveOperationalUnitIdForSubmit`), `LogSheetService`,
`LogSheetTemplateService`, `NfcFaultReportService`, `CustomLogSheetService`,
`LogSheetWebController`, `BootstrapController`, and the post-login redirect in
`WebSecurityConfig`.

**Effect of duplicating `HIGH_USER`:** the copy has all 90 of its permissions but is treated as
unit-scoped, so it sees only the units it is assigned to. It also lands on `/my-inbox` after
login instead of the dashboard.

## 3b. `isAdmin()` — an allow-list

| Rule | Code | What the copy loses |
|---|---|---|
| View another user's import job | `ImportJobService.canViewJob` | Only sees its own import jobs |
| Complete any sheet on the web | `LogSheetWebCompletionAccess.canCompleteOnWeb` | Must be the assignee |
| Template management bypass | `LogSheetTemplateService.assertCanManageUnit` | Falls through to the HIGH_USER check |
| Template visibility | `LogSheetTemplateService` (line ~159) | Sees only supervised units' templates |
| Assignment / lifecycle overrides | `LogSheetAssignmentService`, `LogSheetService` | Restricted to normal rules |
| NFC fault report review / delete | `NfcFaultReportService` | Cannot review or delete |

## 3c. Named-role checks

| Check | Where | Consequence for a copy |
|---|---|---|
| `hasRole("HIGH_USER")` | `LogSheetTemplateService.canEditOrDelete()` | **Cannot create or edit any template** |
| `hasRole("SUPERVISOR")` | `AssetStatusRequestService.requireDecider()` | Cannot decide asset status change requests |
| `hasRole("SUPERVISOR")` | `LogSheetTemplateService` (line ~162) | Templates not listed |
| `hasRole("SENIOR_OPERATOR")` | `LogSheetWebCompletionAccess`, `LogSheetService` | No web completion — mobile only |

> **Not RBAC:** `ExcelImportService` also matches the strings `"SUPERVISOR"` and `"OPERATOR"`,
> but that is parsing the `role` column of the **unit-staff import sheet** into a `StaffRole`
> (a unit membership, not an application role). Different concept, same words.

## What to do about it

- **Prefer duplicating, then verifying.** After copying a role, test the specific action you
  expect it to perform. Permissions alone do not tell you.
- **A custom role cannot substitute for `ADMIN` or `HIGH_USER`.** If someone needs plant-wide
  visibility, they need the real role.
- If a rule ever needs to apply to custom roles, convert it from a role-code check to a
  permission check — but note the rules above are deliberately coarse: "who may see the whole
  plant" is a different kind of decision from "who may call this endpoint", which is why it is
  not expressed as an endpoint permission today.

---

# 4. Adding an endpoint (mandatory)

Permissions are **not** auto-discovered from controllers. For every **new** authority string:

1. Add a constant in `PermissionCodes`.
2. Guard the handler with `@PreAuthorize("hasAuthority('METHOD:/path')")`.
3. **Ship a Flyway migration** inserting the `permissions` row, plus `role_permissions` for
   every role that should receive it. V1's blanket grant to `ADMIN` was a one-time snapshot and
   does **not** cover rows a later migration inserts.
4. Add the scope/object check in the service if the resource belongs to a unit.

Reuse an existing authority when the URL is a variant of an existing capability — export,
options, draft, bulk-delete. `GET …/export` reuses the list authority; `POST …/delete-bulk`
reuses `POST:…/{id}/delete`; batch-import cancel/abandon/delete all reuse `POST:/batch-import`.
The number of `@PreAuthorize` mappings therefore exceeds the number of seeded permissions,
which is expected.

**Forbidden:** creating a permission only in the Roles UI, only with ad-hoc SQL, or only in
Java bootstrap code. Other environments will miss it and `@PreAuthorize` will deny everyone.

---

# 5. Findings from the access review

State as reviewed. Re-run the queries in [§6](#6-how-to-re-audit) after any change.

## F1 — Master-data lists have no unit filter

**Status: open. Not currently exploitable; latent.**

All six master-data list/export handlers read the repository directly:

```
Location    locationRepository::findAll        PlantSystem   plantSystemRepository::findAll
MainFunction mainFunctionRepository::findAll   SubFunction   subFunctionRepository::findAll
AssetEntry  assetEntryRepository::findAll      AssetClass    assetClassRepository::findAll
```

None of them reference `AssetAccessService` or any scope service. Their `/export` siblings are
likewise unfiltered.

This is safe **today only because** those permissions are granted exclusively to `ADMIN` and
`HIGH_USER`, both plant-wide. But the Roles page lets an administrator tick any permission onto
any role. The moment `GET:/asset-entries` — or its export — is granted to a unit-scoped custom
role, that user receives the entire plant's asset register, and no code path prevents it.

A scoped alternative already exists and is used by the reports:
`AssetAccessService.findVisibleAssets`.

**Remediation plan**
1. Swap `AssetEntryWebController` list/export to `assetAccessService.findVisibleAssets(q, pageable)`.
   Behaviour is unchanged for `ADMIN`/`HIGH_USER` because `visibleUnitIds()` returns `null` for
   them.
2. For the five hierarchy controllers, add per-level visible-id queries. **Mind the null trap**
   (gotcha #58 in [AGENTS.md](../AGENTS.md)): `null` means *unrestricted* and needs its own
   branch, or admins get an empty list.
3. Integration test: a unit-scoped user holding `GET:/asset-entries` must not see assets outside
   their units.
4. Minimum viable alternative if the code is not changed: warn on the Roles page that
   `master-data` permissions carry no unit filter.

## F2 — Supervisors cannot create templates (documentation was wrong)

**Status: fixed in the docs. Behaviour unchanged and correct.**

The README claimed `SUPERVISOR` had `GET:` **and** `POST:/log-sheet-templates` and "may create
templates for supervised units". Neither is true:

- the seed grants `SUPERVISOR` only `GET:/log-sheet-templates`;
- `LogSheetTemplateService.canEditOrDelete()` returns `isAdmin() || hasRole("HIGH_USER")`, so
  even a granted endpoint would be refused.

Defence in depth working as intended; the documentation was simply ahead of the code. If
supervisors *should* be able to create templates, both the seed and `canEditOrDelete()` must
change — `assertCanManageUnit` already contains the per-unit rule that would then apply.

## F3 — NFC lookup is plant-wide

**Status: open. A design decision, not a defect.**

`GET /api/asset-entries/nfc/{nfcTagId}` returns any asset for any tag with no unit check, and
`OPERATOR` holds it. Operationally defensible — the operator is physically standing at the
asset — but if tags are sequential or guessable it permits enumeration of assets outside the
caller's units.

**If you decide to restrict it:** filter in `AssetEntryService.findByNfcTag` via
`AssetAccessService`, and return **404, not 403**, for out-of-scope hits. A 403 confirms the tag
exists, which is the thing being protected.

## F4 — Custom roles may be granted admin-category permissions

**Status: accepted risk / governance.**

Nothing prevents an administrator from ticking `POST:/users` or `POST:/roles` onto a custom
role. That is the administrator's prerogative and they are already trusted, but there is no
guard and no warning. Note that role duplication copies permissions in full, so a copy of
`ADMIN` carries every `admin`-category permission — while, per [§3](#3-️-access-that-depends-on-the-roles-code-not-its-permissions), still not
being treated as an admin by the code.

---

# 6. How to re-audit

Four checks. All of them are cheap and should be repeated after touching permissions.

**Every handler is guarded** — should list only `/login`, `/api/auth/login`, `/api/health`:

```bash
grep -rn "@PreAuthorize" -L src/main/java/com/hnp/backendofflinefirst/web src/main/java/com/hnp/backendofflinefirst/controller
```

**Code and database agree** — both lists should be empty apart from the two `WebSecurityConfig`
entries (`GET:/actuator/**`, `GET:/v3/api-docs/**`, which are gated there rather than by
`@PreAuthorize`):

```bash
grep -rhoE "hasAuthority\('[^']+'\)" src/main/java --include="*.java" | sed "s/hasAuthority('//; s/')//" | sort -u > /tmp/code.txt
grep -hoE "'[A-Z]+:/[^']*'" src/main/resources/db/migration/*.sql | tr -d "'" | sort -u > /tmp/db.txt
comm -23 /tmp/code.txt /tmp/db.txt   # checked in code, never seeded -> endpoint unreachable
comm -13 /tmp/code.txt /tmp/db.txt   # seeded, never checked -> dead permission
```

**No unexpected admin-category grant:**

```sql
SELECT r.code, p.code FROM roles r
  JOIN role_permissions rp ON rp.role_id = r.id
  JOIN permissions p ON p.id = rp.permission_id
 WHERE p.category = 'admin' AND r.code <> 'ADMIN'
 ORDER BY r.code, p.code;
-- expected: HIGH_USER + the three batch-import rows, nothing else
```

**Grant totals per role** — compare against the table in [§2](#2-the-five-system-roles):

```sql
SELECT r.code, p.category, count(*)
  FROM roles r
  JOIN role_permissions rp ON rp.role_id = r.id
  JOIN permissions p ON p.id = rp.permission_id
 GROUP BY 1, 2 ORDER BY 1, 2;
```

---

# 7. Other security surfaces

| Surface | State |
|---|---|
| **CSRF** | Enabled on the web chain; disabled **only** for `/api/**` (JWT, stateless). A `fetch()` POST without the token is silently swallowed — see gotcha #69 and use `AppCsrf.postJson`. |
| **Actuator** | `/actuator/health/liveness` and `/readiness` are public probes; everything else needs `GET:/actuator/**` (ADMIN). |
| **OpenAPI / Swagger** | Enabled in every environment but gated behind `GET:/v3/api-docs/**` (ADMIN). Never make it `permitAll()`. |
| **Mobile sessions** | JWTs are stateful — every token carries a `jti` backed by an `api_sessions` row. A valid signature alone does not authenticate. One device per user; a new login supersedes the others. |
| **Web sessions** | One browser per user (`maximumSessions(1)`). `/web-sessions` addresses rows by a SHA-256 digest, so raw `JSESSIONID`s never reach the page. |
| **Login throttle** | `LoginAttemptService` checks **before** password verification, including before the LDAP bind — this stops an attacker using this app to trip Active Directory's lockout against a real employee. |
| **Log files** | Not web-served. `LogSanitizer` masks password/token/secret. Note it does **not** currently mask `nationalCode` or `phoneNumber`. |
| **Bootstrap admin** | `admin` / `admin123`, created only when no ADMIN user exists. Change it before the system leaves your desk. |

---

## Related

- **[hierarchy.md](hierarchy.md)** — how unit scope is derived from the location tree
- **[log-sheets.md](log-sheets.md)** — the lifecycle these permissions gate
- **[schema.md](schema.md)** — `roles`, `permissions`, `role_permissions`, `user_roles`
- **[../AGENTS.md](../AGENTS.md)** — §2b and §5, plus the numbered traps
- **[../README.md](../README.md)** — the role descriptions in prose
