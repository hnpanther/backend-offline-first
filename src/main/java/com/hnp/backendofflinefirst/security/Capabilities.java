package com.hnp.backendofflinefirst.security;

import java.util.List;

/**
 * Access decisions that are <b>not</b> about calling a particular endpoint.
 *
 * <h2>Why these exist</h2>
 * A rule like "may see every operational unit" or "may complete a sheet they were not assigned"
 * used to be answered by comparing the user's <em>role code</em> —
 * {@code SecurityUtils.isAdmin()}, {@code hasRole("HIGH_USER")}, and
 * {@code isUnitScopedOnly()} which was defined as {@code !ADMIN && !HIGH_USER}. That made roles
 * un-copyable. The "duplicate role" feature copies a role's <em>permissions</em> and gives the
 * copy a <em>new code</em>, so every rule written against the original's code stopped
 * recognising it: a duplicate of ADMIN held all 123 permissions and still could not view
 * another user's import job or look outside its own units.
 *
 * <h2>Why they live in the permissions table</h2>
 * Role duplication already copies {@code role_permissions}. Storing capabilities there means
 * the copyability problem is solved by construction, and the Roles page, {@code AppUserDetails}
 * and the mobile login response all carry them with no new machinery. They are marked
 * {@code category = 'capability'} with null method/path, and their codes use a {@code CAP:}
 * prefix so nothing ever tries to parse one as a route.
 *
 * <h2>The rule when adding one</h2>
 * <b>Always phrase a capability positively — as something the holder may do.</b> Absence must
 * mean "restricted". The old {@code isUnitScopedOnly()} was a deny-list and therefore failed
 * safe for unknown roles; {@code CAP:SCOPE_PLANT_WIDE} keeps that property only because it is
 * worded the other way round. A capability named {@code CAP:UNIT_SCOPED} would invert it and
 * hand every custom role the whole plant.
 *
 * @see <a href="file:../../../../../../../docs/security.md">docs/security.md</a>
 */
public final class Capabilities {

    private Capabilities() {}

    /** Sees every operational unit. Absence means "filtered to the units I am assigned to". */
    public static final String SCOPE_PLANT_WIDE = "CAP:SCOPE_PLANT_WIDE";

    /** May create, edit or delete log-sheet templates at all. */
    public static final String TEMPLATE_MANAGE = "CAP:TEMPLATE_MANAGE";
    /** May do so for a unit they do not personally supervise. */
    public static final String TEMPLATE_MANAGE_ANY_UNIT = "CAP:TEMPLATE_MANAGE_ANY_UNIT";

    /** Template list is unfiltered. */
    public static final String TEMPLATE_VIEW_ANY_UNIT = "CAP:TEMPLATE_VIEW_ANY_UNIT";
    /** Template list shows the units they supervise. Absence means it shows nothing. */
    public static final String TEMPLATE_VIEW_SUPERVISED = "CAP:TEMPLATE_VIEW_SUPERVISED";

    /** May approve or reject a proposed asset status change. */
    public static final String ASSET_STATUS_DECIDE = "CAP:ASSET_STATUS_DECIDE";

    /** May complete any log sheet in the browser, without being its assignee. */
    public static final String LOGSHEET_COMPLETE_WEB_ANY = "CAP:LOGSHEET_COMPLETE_WEB_ANY";
    /** May complete a sheet already assigned to them in the browser rather than only in the app. */
    public static final String LOGSHEET_COMPLETE_WEB_SELF = "CAP:LOGSHEET_COMPLETE_WEB_SELF";

    /** Exercises supervisor powers over units they do not supervise: assign, reassign, extend. */
    public static final String SUPERVISE_ANY_UNIT = "CAP:SUPERVISE_ANY_UNIT";

    /** Sees import jobs submitted by other people. */
    public static final String IMPORT_JOB_VIEW_ALL = "CAP:IMPORT_JOB_VIEW_ALL";

    /** May mark an NFC fault report reviewed — an assertion, hence narrower than reading them. */
    public static final String NFC_FAULT_REVIEW = "CAP:NFC_FAULT_REVIEW";

    /** Every capability, in the order they appear above. Used by tests and the seed check. */
    public static final List<String> ALL = List.of(
            SCOPE_PLANT_WIDE,
            TEMPLATE_MANAGE,
            TEMPLATE_MANAGE_ANY_UNIT,
            TEMPLATE_VIEW_ANY_UNIT,
            TEMPLATE_VIEW_SUPERVISED,
            ASSET_STATUS_DECIDE,
            LOGSHEET_COMPLETE_WEB_ANY,
            LOGSHEET_COMPLETE_WEB_SELF,
            SUPERVISE_ANY_UNIT,
            IMPORT_JOB_VIEW_ALL,
            NFC_FAULT_REVIEW);

    /** The category every capability row carries, so the Roles UI can group them apart. */
    public static final String CATEGORY = "capability";

    public static boolean isCapability(String code) {
        return code != null && code.startsWith("CAP:");
    }
}
