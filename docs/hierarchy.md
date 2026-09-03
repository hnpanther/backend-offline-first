# The Asset Hierarchy and Access Scope

How equipment is placed in the plant, how that placement decides who may see it, and — the
part that causes real bugs — **what must happen when you move something.**

Read this before changing anything under `AssetHierarchyService`, `AssetAccessService` or
`AssetUnitScopeSql`.

---

# 1. The two trees

There are **two independent hierarchies**, and almost every access-control bug in this system
has come from confusing them.

```
PHYSICAL (where the equipment is)          ORGANISATIONAL (who is responsible)

  Location ──────────────┐                  Operational Unit
     │                   │                        │  (self-nesting tree)
     ├─ Plant System     │                        ├─ Sub-unit
     │     │             │                        │     └─ Sub-sub-unit
     │     └─ Main Fn    │                        └─ Sub-unit
     │           │       │
     ├─ Main Fn  │       │                  people attach here:
     │     │     │       │                    unit_supervisors
     │     └─ Sub Fn ◄───┤                    unit_operators
     │           │       │
     └─ Sub Fn ◄─────────┘
           │
        Asset Entry
```

They meet at exactly one place:

```
                  location_units
   Location  ◄────────────────────►  Operational Unit
```

**`location_units` is the join, and it is the single most important table for access control.**
Everything a unit-scoped user can see is derived by walking from their units, through this
table, into the physical tree, down to assets.

A location may belong to **several** units. A shared utilities area genuinely is the
responsibility of more than one team, and forcing a single owner meant somebody had to be wrong.

---

# 2. The physical tree in detail

Five levels, but the shape is looser than "five levels" suggests:

| Level | Table | May self-nest | May attach directly to |
|---|---|---|---|
| Location | `locations` | ✅ `parent_id` | — (the root) |
| Plant System | `plant_systems` | ✅ `parent_id` | Location |
| Main Function | `main_functions` | ✅ `parent_id` | Location **or** Plant System |
| Sub Function | `sub_functions` | ✅ `parent_id` | Location **or** Plant System **or** Main Function |
| Asset | `asset_entries` | ❌ | Sub Function (**required**) |

**Levels can be skipped.** A sub-function may hang straight off a location with no system and
no main function in between. Real plants are not uniformly deep, and forcing intermediate rows
that mean nothing would make the data lie about the plant.

**Only the asset's parent is mandatory.** `asset_entries.sub_function_id` is `NOT NULL`; every
other parent link is nullable.

**An asset has no children, and that is the model's deliberate shape** — `asset_entries` has no
`parent_id`. A component that belongs to another asset (the GPU in a PC, a pump's bearing) is
therefore not expressible as a child *asset* today. The question comes up often enough that the
options, their costs, and the one blocker that decides them — the NFC flow assumes **one scan =
one asset** — are worked out in [roadmap.md §1](roadmap.md). Read that before adding a
`parent_id`; the cheapest answer needs no schema change at all.

## Ancestry is denormalised, on purpose

A `sub_function` carries **every ancestor id**, not just its direct parent:

```sql
sub_functions (
    parent_id        BIGINT,   -- direct: another sub-function
    main_function_id BIGINT,   -- ancestor
    system_id        BIGINT,   -- ancestor
    location_id      BIGINT    -- ancestor
)
```

A sub-function placed under a main function also carries that main function's `system_id` and
`location_id`.

**Why:** the alternative is a four-way join or a recursive CTE on every scoped asset query —
the hottest path in the application, hit on every list, every report, and every log sheet
generation. Denormalising trades write complexity for read speed, and reads outnumber writes
here by orders of magnitude.

**The cost:** the ancestry columns can go stale, and keeping them true is the job of exactly
one class.

---

# 3. `AssetHierarchyService` — the one place that writes placement

[`AssetHierarchyService`](../src/main/java/com/hnp/backendofflinefirst/service/AssetHierarchyService.java)
is the **only** class allowed to set parent or ancestry columns. It does three things:

1. **Applies a chosen direct parent and fills the ancestry** — `applySubFunctionParent(sf, type, id)`
2. **Cascades ancestry to descendants on save** — the part that is easy to forget
3. **Resolves a scope to the sub-functions beneath it** — a tree walk

Scope type constants:

```java
public static final String SCOPE_LOCATION      = "location";
public static final String SCOPE_SYSTEM        = "system";
public static final String SCOPE_MAIN_FUNCTION = "mainFunction";
public static final String SCOPE_SUB_FUNCTION  = "subFunction";
```

## Setting a parent

Never assign `locationId` / `systemId` / `mainFunctionId` by hand. Call:

```java
hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mfId);
sf = hierarchyService.saveSubFunction(sf);
```

`applySubFunctionParent` sets the direct parent **and** copies down the whole ancestor chain
from it. Setting one field yourself leaves the others pointing at the old place, and the row
then appears in one scope's queries and not another's — a bug that shows up as "this asset is
missing from the report" long after the edit.

## Saving, and what cascades

Each level's save has an overload that takes the **prior** parent values, which is how it knows
whether a move happened:

```java
public PlantSystem savePlantSystem(PlantSystem ps, Long priorLocationId, Long priorParentId);
public MainFunction saveMainFunction(MainFunction mf, Long priorSystemId, Long priorLocationId, Long priorParentId);
public SubFunction  saveSubFunction(SubFunction sf, Long priorMainFunctionId, Long priorSystemId, Long priorLocationId, Long priorParentId);
```

> **If you edit a row without passing the prior values, descendants are not refreshed.**
> The row you edited will be right and everything beneath it will be wrong. Load the entity,
> keep the old ids, mutate, then save with them.

### What cascades where

| You change | Cascades to |
|---|---|
| **Location's parent** | **Nothing.** See below. |
| **Location ↔ unit links** | Nothing stored — scope is resolved at query time |
| **Plant system's location** | Descendant systems, their main functions, their sub-functions |
| **Plant system's parent** | Same |
| **Main function's system or location** | Descendant main functions, and all sub-functions beneath them |
| **Sub function's parent chain** | Descendant sub-functions |
| **Asset's sub-function** | Nothing — an asset is a leaf ([why, and what a component would need](roadmap.md)) |

### Reparenting a location does *not* cascade — and that is correct

```java
/**
 * Persists a location. Reparenting a location does not cascade denormalized
 * fields onto systems or functions — downstream rows keep the same
 * locationId; scope walks read the tree at query time.
 */
```

A system attached to location L still belongs to L after L moves under a different parent. The
`location_id` on downstream rows is still true. What changed is L's own position, and the
recursive `loc_tree` walk in the scope CTE reads that at query time.

This is the one place the denormalisation stops, and it stops because there is nothing to fix.

## Uniqueness on save

Every save runs `MasterDataUniquenessValidator`. Codes are unique **case-insensitively across
the whole plant** (`ux_*_code_lower`), and `sub_functions.tag` is unique too — it is the NFC
identity, and two positions answering to the same chip would be unresolvable.

---

# 4. NFC identity and asset replacement

`sub_functions.tag` is what is physically written into the chip's NDEF Record 1.

**The chip is bolted to the position, not to the equipment.** When a pump is replaced:

1. The old asset is set `active = false` — it stays, with all its readings.
2. The new asset is created under the **same sub-function** and inherits the same tag.
3. The chip on the wall never changes.

This is what `ux_asset_entries_active_sub_function` enforces:

```sql
CREATE UNIQUE INDEX ux_asset_entries_active_sub_function
    ON asset_entries (sub_function_id) WHERE active;
```

**Partial on `WHERE active`** — that is the whole trick. One *installed* asset per position,
unlimited *retired* ones. Without the partial clause you would have to delete the old asset to
install a new one, taking every reading ever recorded against it.

Practical consequence: **deactivate the old asset before activating the new one**, or the
insert fails on the unique index.

`asset_entries.nfc_serial` is separate — the chip's unreadable-only hardware UID. Checking it
is what defeats a cloned tag; the logical tag can be written by anyone with a phone.

---

# 5. Access scope — the part that must be right

## Supervisors inherit downward, operators do not

This asymmetry is the reason `unit_supervisors` and `unit_operators` are two tables rather than
one with a `kind` column.

| | Sees |
|---|---|
| **Supervisor of unit X** | X **and every unit beneath it**, recursively |
| **Operator of unit X** | X **only** |

**Why:** supervising a unit means being accountable for what is under it — that is what the
word means. Being an operator of a unit means working in that unit. If operators inherited
downward, attaching one operator to a top-level unit would silently hand them the whole plant.

## One person who is both supervisor and operator of the same unit

This is **allowed and supported**, not an edge case that slipped through. `unit_supervisors` and
`unit_operators` are separate tables, each keyed `(unit_id, user_id)`, and nothing constrains one
against the other. Both routes in reach it: the operational-unit form has two independent user
pickers, and the staff Excel import writes each row to its own table. On a small shift the
supervisor often walks the round themselves, which is the situation this exists for.

`getAccessibleUnitIds` unions the two, so the unit is reached once. What changes is what the
person may *do* in it:

| | Effect |
|---|---|
| **Filling on the web** | Opens up. `LogSheetWebCompletionAccess.canCompleteOnWeb` admits the assignee who is also the unit's supervisor; a plain operator of the same unit is mobile-only |
| **Assigning work** | They can assign a sheet to themselves — `requireSupervisorAndTarget` wants the actor to supervise the unit and the target to operate it, and both are true |
| **Approving** | They can approve a round they walked. **Deliberate** — see the reasoning on `LogSheetAssignmentService.approve`: refusing would leave those sheets permanently unapprovable. If segregation of duties is ever wanted, that method is where the rule belongs |
| **Scope** | Unchanged and still asymmetric: supervision reaches the sub-units, operation does not. They supervise the branch and operate one unit of it |

What does **not** happen, each for a specific reason worth keeping:

- **Nothing appears twice.** `getAccessibleUnitIds` returns a `Set`, and the inbox's two buckets
  are disjoint by status — an assigned sheet is not in the pool.
- **Their own work stays out of the team list.** `findTeamOpenForSupervisor` queries
  `findOpenInUnitsAssignedToOthers(..., supervisorId)`, which excludes the supervisor's own
  sheets, so a round they are walking is not also listed as a round to oversee.
- **`getPrimaryUnitId` answers with the supervised unit**, because it checks
  `unit_supervisors` first.

No rule anywhere asks whether someone is a supervisor *or* an operator as an exclusive choice;
every one of them asks `isSupervisorOf` and `isOperatorOf` independently. That is what makes the
combination safe, and it is the property to preserve when adding a rule.

## `visibleUnitIds()` returns `null` for an unrestricted admin

`AssetAccessService.visibleUnitIds()` has three possible answers, and they are **not**
interchangeable:

| Return | Meaning |
|---|---|
| `null` | unrestricted — an admin, everything is visible |
| empty set | this user may see **nothing** |
| non-empty | exactly these units |

> **A CTE cannot express "unrestricted."** `unit_id IN (:unitIds)` with a null binding matches
> nothing, which silently turns an admin into a user with no access. Every scoped query must be
> **split into a scoped variant and an unrestricted variant** — this is why
> `findSilentAssets` needed a `findSilentAssetsUnrestricted` twin after the data-quality report
> returned nothing at all for administrators.

And an *empty* list must be short-circuited before it reaches SQL, because `IN ()` either
matches everything or fails outright depending on dialect. The convention here is to substitute
a sentinel:

```java
if (scopedAssetIds != null && scopedAssetIds.isEmpty()) {
    scopedAssetIds = List.of(-1L);
}
```

## Two scopes, deliberately different

There are **two** scope resolutions and using the wrong one is a real defect in both directions.

### Registry scope — `findVisible*` / `SCOPED_SUBFUNCTIONS_CTE`

*"Which assets sit in locations this unit owns?"* — the right rule for master-data lists.

```sql
WITH RECURSIVE loc_roots AS (
    SELECT DISTINCT location_id AS id FROM location_units WHERE unit_id IN (:unitIds)
),
loc_tree AS (                       -- locations, recursively downward
    SELECT id FROM loc_roots
    UNION ALL
    SELECT l.id FROM locations l INNER JOIN loc_tree t ON l.parent_id = t.id
),
systems AS (
    SELECT id FROM plant_systems WHERE location_id IN (SELECT id FROM loc_tree)
),
main_roots AS (
    SELECT id FROM main_functions
    WHERE location_id IN (SELECT id FROM loc_tree) OR system_id IN (SELECT id FROM systems)
),
main_tree AS (                      -- main functions, recursively downward
    SELECT id FROM main_roots
    UNION ALL
    SELECT mf.id FROM main_functions mf INNER JOIN main_tree t ON mf.parent_id = t.id
),
scoped_sf AS (
    SELECT id FROM sub_functions
    WHERE location_id      IN (SELECT id FROM loc_tree)
       OR system_id        IN (SELECT id FROM systems)
       OR main_function_id IN (SELECT id FROM main_tree)
)
```

Note the three-way `OR` in `scoped_sf`: it exists precisely because levels can be skipped. A
sub-function hanging directly off a location has no `system_id` and no `main_function_id`, and
would be invisible to a query that only checked one of them.

### Reporting scope — `findReportable*` / `REPORTABLE_ASSETS_CTE`

*"Which assets is this user responsible for?"* — deliberately **wider**, because responsibility
arrives through the log sheet, not through location ownership.

```sql
reportable_assets AS (
    SELECT a.id FROM asset_entries a
    INNER JOIN scoped_sf s ON a.sub_function_id = s.id
    UNION
    SELECT e.asset_id AS id
    FROM log_sheet_entries e
    INNER JOIN log_sheets ls ON ls.id = e.log_sheet_id
    WHERE ls.operational_unit_id IN (:unitIds) AND e.asset_id IS NOT NULL
)
```

The union keeps location-owned assets (so an asset with no log sheet yet is still reportable by
its owning unit) **and adds** every asset reached through a sheet the user's units are
responsible for.

**Why this is not optional:** a template with `restrict_scope_to_unit = false` deliberately
puts assets from outside the unit's own locations onto that sheet. Filtering reports by
location ownership therefore hid the readings of work the user had just been required to
perform — and where `location_units` was not populated at all, it hid *every reading in the
system* from every unit-scoped user.

### Which to use

| You are building | Use |
|---|---|
| A master-data list (assets, locations, sub-functions) | `findVisible*` — registry scope |
| A report, a history page, a status-change queue | `findReportable*` — reporting scope |
| A picker feeding an action | **the same scope the save validates against**, or the list will offer options the save then refuses |

---

# 6. Scope on log sheet templates

A template names a **scope** (where) and an **operational unit** (who), and one flag decides
how they combine:

| `restrict_scope_to_unit` | Assets on the generated sheet |
|---|---|
| `true` (default) | Assets in the scope **that also sit in the unit's locations** |
| `false` | **All** assets in the scope, regardless of which unit owns the location |

`false` is for a shared area one team reads on everyone's behalf. It is also the reason
reporting scope has to be wider than registry scope — see above.

---

# 7. Changing the hierarchy: a checklist

**Moving a sub-function to a different parent**

1. Load it; keep `priorMainFunctionId`, `priorSystemId`, `priorLocationId`, `priorParentId`.
2. `applySubFunctionParent(sf, newType, newId)` — never set ancestry fields by hand.
3. `saveSubFunction(sf, prior…)` — **with** the prior values, so descendants refresh.
4. Consider: does this move the assets beneath it into or out of somebody's scope? A log sheet
   already generated is unaffected (it froze its asset list), but the next generation changes.

**Attaching a location to an operational unit**

1. `replaceLocationUnits(locationId, unitIds)` — it replaces, it does not add.
2. Nothing is denormalised; scope changes take effect on the next query.
3. Everything under that location, to any depth, is now in that unit's scope.

**Replacing a physical asset**

1. Deactivate the old asset first (`active = false`), or the partial unique index rejects the new one.
2. Create the new asset under the **same** sub-function.
3. Leave the NFC chip alone — it belongs to the position.
4. Both assets keep their readings; `asset_activation_history` records the transition.

**Adding a level or changing the CTE**

1. Update `AssetUnitScopeSql` — **both** CTEs.
2. Check every `findVisible*` / `findReportable*` pair.
3. Check the unrestricted-admin twin of each query exists.
4. Add an integration test with a **scoped** user, not just an admin: an admin passes almost
   any scope bug, because their scope is "everything."

## Common failure signatures

| Symptom | Likely cause |
|---|---|
| An asset is missing from one list but present in another | Ancestry columns stale — saved without prior values |
| An admin sees nothing while a supervisor sees data | A scoped query with no unrestricted variant (`:unitIds` bound to null) |
| A supervisor cannot see readings from a round they performed | Registry scope used where reporting scope was needed |
| A picker offers an asset the save then refuses | Picker and validation using different scopes |
| Inserting a new asset fails on a unique index | The old asset at that sub-function is still `active` |

## Related

- **[schema.md](schema.md)** — the tables, indexes and constraints
- **[log-sheets.md](log-sheets.md)** — how scope decides what lands on a round
- **[reports.md](reports.md)** — which scope each report uses
- **[security.md](security.md)** — the roles that consume this scope, and where a role code is checked instead of a permission
- **[AGENTS.md](../AGENTS.md)** — the `visibleUnitIds()` null trap, written up as a rule
