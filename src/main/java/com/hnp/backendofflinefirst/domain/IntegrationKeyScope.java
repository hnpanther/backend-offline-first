package com.hnp.backendofflinefirst.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What one integration API key may read (V20): the log-sheet categories its rounds must belong
 * to, and the finished statuses it may see. Each dimension is {@code null} for «no restriction».
 *
 * <h2>The two dimensions answer differently when a caller reaches past them</h2>
 *
 * <ul>
 *   <li><b>A status outside the scope, asked for by name, is refused (403).</b> The statuses are a
 *       closed list of five words the API documents, so saying «this key may read SUBMITTED and
 *       APPROVED» reveals nothing — and silently answering with a subset would be the 200-with-
 *       the-wrong-rows failure this API refuses everywhere else.</li>
 *   <li><b>A category outside the scope simply matches nothing.</b> Categories are this plant's
 *       data: answering «that category exists but you may not read it» would let a key probe
 *       which categories the plant has, one request at a time.</li>
 * </ul>
 *
 * <h2>The default status set under a status scope</h2>
 *
 * <p>An unscoped caller that names no status gets the completed rounds
 * ({@link IntegrationLogSheetQuery#DEFAULT_STATUSES}). A scoped one gets the completed statuses
 * <em>it may read</em>; if it may read none of them — a key issued only for the voided or
 * cancelled rounds — it gets everything it may read, because that is what the key is for and an
 * always-empty default would look exactly like a quiet plant.
 *
 * <h2>Never empty, only null</h2>
 *
 * <p>An empty set would be a restriction nothing satisfies, one keystroke from {@code null} in
 * every mapping. The constructor refuses it, so «restricted to nothing» cannot be constructed.
 *
 * @param categoryIds the categories a round must be frozen into, or null for any round
 * @param statuses    the exposable statuses this key may read, or null for all of them
 */
public record IntegrationKeyScope(Set<Long> categoryIds, Set<LogSheetStatus> statuses) {

    /** A key that reads everything the API exposes — every key issued before V20. */
    public static final IntegrationKeyScope UNRESTRICTED = new IntegrationKeyScope(null, null);

    public IntegrationKeyScope {
        if (categoryIds != null) {
            if (categoryIds.isEmpty()) {
                throw new IllegalArgumentException("A category scope cannot be empty; use null for every category.");
            }
            categoryIds = Set.copyOf(categoryIds);
        }
        if (statuses != null) {
            if (statuses.isEmpty()) {
                throw new IllegalArgumentException("A status scope cannot be empty; use null for every status.");
            }
            for (LogSheetStatus status : statuses) {
                if (!IntegrationLogSheetQuery.EXPOSABLE_STATUSES.contains(status)) {
                    throw new IllegalArgumentException("Status " + status
                            + " is not exposed by the integration API and cannot be in a key's scope.");
                }
            }
            statuses = Set.copyOf(statuses);
        }
    }

    /** Builds a scope from what an administrator ticked; nothing ticked is «no restriction». */
    public static IntegrationKeyScope of(Collection<Long> categoryIds, Collection<LogSheetStatus> statuses) {
        Set<Long> categories = categoryIds == null ? Set.of()
                : categoryIds.stream().filter(id -> id != null).collect(Collectors.toSet());
        Set<LogSheetStatus> chosen = statuses == null ? Set.of()
                : statuses.stream().filter(s -> s != null).collect(Collectors.toSet());
        return new IntegrationKeyScope(categories.isEmpty() ? null : categories,
                chosen.isEmpty() ? null : chosen);
    }

    public boolean restrictsCategories() {
        return categoryIds != null;
    }

    public boolean restrictsStatuses() {
        return statuses != null;
    }

    public boolean isUnrestricted() {
        return categoryIds == null && statuses == null;
    }

    /**
     * The statuses a list request reads.
     *
     * @param requested what the caller asked for, or what the query defaulted to
     * @param named     whether the caller named them, rather than taking the default
     * @throws IntegrationScopeException when a named status is outside this key's scope
     */
    public Set<LogSheetStatus> effectiveStatuses(Set<LogSheetStatus> requested, boolean named) {
        if (statuses == null) {
            return requested;
        }
        if (named) {
            List<String> refused = requested.stream()
                    .filter(status -> !statuses.contains(status))
                    .map(Enum::name).sorted().toList();
            if (!refused.isEmpty()) {
                throw new IntegrationScopeException("This API key may not read status " + refused
                        + ". The statuses it may read are " + sortedNames(statuses) + ".");
            }
            return requested;
        }
        Set<LogSheetStatus> defaults = EnumSet.noneOf(LogSheetStatus.class);
        defaults.addAll(IntegrationLogSheetQuery.DEFAULT_STATUSES);
        defaults.retainAll(statuses);
        return defaults.isEmpty() ? statuses : Set.copyOf(defaults);
    }

    /**
     * The categories a list request reads, after the key's scope narrows what the caller asked.
     *
     * @param requested the category ids the caller's codes resolved to, or null when the caller
     *                  named none. An empty set means «named only codes that match nothing».
     * @return null for no restriction; otherwise the ids to keep — possibly empty, which the
     *         caller must answer with an empty page and never pass on as «no restriction»
     */
    public Set<Long> effectiveCategoryIds(Set<Long> requested) {
        if (categoryIds == null) {
            return requested;
        }
        if (requested == null) {
            return categoryIds;
        }
        Set<Long> kept = new HashSet<>(requested);
        kept.retainAll(categoryIds);
        return Set.copyOf(kept);
    }

    /** Whether one round is inside this key's scope — for the detail endpoint. */
    public boolean permits(LogSheetStatus status, Long categoryId) {
        if (statuses != null && (status == null || !statuses.contains(status))) {
            return false;
        }
        return categoryIds == null || (categoryId != null && categoryIds.contains(categoryId));
    }

    /** The stored form of {@link #statuses()}: sorted names joined by commas, or null. */
    public String statusesColumn() {
        return statuses == null ? null : String.join(",", sortedNames(statuses));
    }

    /**
     * Reads {@code api_keys.scope_statuses}. A name the API no longer exposes is dropped rather
     * than failing every request the key makes. <b>A column left with no name at all fails
     * closed</b> — the key's requests answer 500 — because returning {@code null} there would
     * widen a restricted key to every status. {@code ck_api_keys_scope_statuses} makes that
     * reachable only through a future change to the exposable set.
     */
    public static Set<LogSheetStatus> parseStatusesColumn(String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        Set<LogSheetStatus> parsed = new LinkedHashSet<>();
        Arrays.stream(column.split(","))
                .map(String::trim)
                .map(LogSheetStatus::fromNullable)
                .filter(status -> status != null && IntegrationLogSheetQuery.EXPOSABLE_STATUSES.contains(status))
                .forEach(parsed::add);
        if (parsed.isEmpty()) {
            // Never widen: a scope whose names all became unknown reads nothing it can name.
            throw new IllegalStateException("api_keys.scope_statuses holds no status the API exposes: " + column);
        }
        return parsed;
    }

    private static List<String> sortedNames(Collection<LogSheetStatus> statuses) {
        return statuses.stream().map(Enum::name).sorted().toList();
    }

    /** A request reaching past its key's scope in a way that is safe to say out loud (403). */
    public static class IntegrationScopeException extends RuntimeException {
        public IntegrationScopeException(String message) {
            super(message);
        }
    }
}
