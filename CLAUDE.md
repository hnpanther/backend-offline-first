# Read this first

Spring Boot 4.1 / Java 25 / PostgreSQL backend for an offline-first industrial log-sheet
system. Persian (RTL) throughout. Its companion PWA lives at
`D:\LocalStorage\Project\FrontEnd\offline-first-pwa`.

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
| [docs/schema.md](docs/schema.md) | Every table, column, index and constraint, with the reasoning. **The current shape of the schema, not a replay of migrations.** |
| [docs/hierarchy.md](docs/hierarchy.md) | Location → Asset, and access scope. **Required reading before touching placement or scope.** |
| [docs/security.md](docs/security.md) | Roles, permissions, the three enforcement layers, and **which rules key off a role's code rather than its permissions** (so a duplicated role does not inherit them). Read before adding an endpoint or a custom role. |
| [docs/log-sheets.md](docs/log-sheets.md) | The core business object: lifecycle, states, endpoints, status requests |
| [docs/jobs.md](docs/jobs.md) | Schedulers, startup runners, async pools, and how each fails |
| [docs/reports.md](docs/reports.md) | Every report and the exact formula behind each number |
| [docs/deployment.md](docs/deployment.md) | **Running it as a service.** WinSW (Windows) and systemd (Linux), where the secrets go, PostgreSQL, backups, and the failures that look like something else |
| [docs/roadmap.md](docs/roadmap.md) | **Designs that are not built, and limits deliberately deferred.** Sub-assets; request rate limiting; the per-tick cost of the mobile inbox — each with the conditions that would make it urgent. Never read it as behaviour — it records what was decided and the current-system constraints each design depends on. (The third-party integration API used to live here; it is built, and now documented in `security.md` §7, `log-sheets.md` §7, `schema.md` and the README.) |

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
| A trap you only found by debugging | **[AGENTS.md](AGENTS.md)** — add a numbered entry with the *why* |
| A user-visible feature | [README.md](README.md) |
| Anything touching the mobile contract | the PWA's `README.md`, `AGENTS.md` and `docs/` too |

## Two standing constraints

- **V1 is a closed baseline.** Every schema change is a new `V{n}__description.sql`. Editing an
  applied migration breaks its Flyway checksum and the application refuses to boot.
- **After a manual local run, kill the port-8081 java process.**

## Verifying

```bash
mvn -o test
```

`ddl-auto=validate` is set, so a successful boot is itself proof that every entity matches the
schema. Live runs have repeatedly found defects that a fully green suite did not — two features
completely dead due to template placement, a status value invisible due to Thymeleaf
truthiness, a report hiding 46 uninspected assets. Run it live before claiming a UI change works.
