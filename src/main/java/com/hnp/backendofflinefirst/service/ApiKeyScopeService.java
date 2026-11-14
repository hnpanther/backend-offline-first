package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.IntegrationKeyScope;
import com.hnp.backendofflinefirst.domain.IntegrationLogSheetQuery;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.ApiKey;
import com.hnp.backendofflinefirst.entity.ApiKeyCategory;
import com.hnp.backendofflinefirst.entity.LogSheetCategory;
import com.hnp.backendofflinefirst.repository.ApiKeyCategoryRepository;
import com.hnp.backendofflinefirst.repository.ApiKeyRepository;
import com.hnp.backendofflinefirst.repository.LogSheetCategoryRepository;
import com.hnp.backendofflinefirst.security.IntegrationClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads and writes what one integration API key may see (V20). The only place a key's scope is
 * assembled from its two stores — {@code api_keys.scope_statuses} and {@code api_key_categories}
 * — so no caller can read one and forget the other.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyScopeService {

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyCategoryRepository apiKeyCategoryRepository;
    private final LogSheetCategoryRepository categoryRepository;

    /**
     * The scope of the key making the current integration request.
     *
     * <p>Read on every request rather than cached on the principal at authentication, so a scope
     * an administrator narrows takes effect on the consumer's very next call. Two primary-key
     * lookups; the key row is already hot from authentication.
     *
     * @throws IllegalStateException without a client — fail closed rather than read everything
     */
    @Transactional(readOnly = true)
    public IntegrationKeyScope scopeOf(IntegrationClient client) {
        if (client == null || client.apiKeyId() == null) {
            throw new IllegalStateException("No integration client on this request.");
        }
        ApiKey key = apiKeyRepository.findById(client.apiKeyId())
                .orElseThrow(() -> new IllegalStateException("The authenticated API key no longer exists."));
        return scopeOf(key, categoryIdsOf(apiKeyCategoryRepository.findByApiKeyId(key.getId())));
    }

    /** Every listed key's scope, in two statements however many keys — for the admin page. */
    @Transactional(readOnly = true)
    public Map<Long, IntegrationKeyScope> scopesOf(Collection<ApiKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> categoriesByKey = new HashMap<>();
        for (ApiKeyCategory row : apiKeyCategoryRepository.findByApiKeyIdIn(
                keys.stream().map(ApiKey::getId).toList())) {
            categoriesByKey.computeIfAbsent(row.getApiKeyId(), k -> new HashSet<>()).add(row.getCategoryId());
        }
        Map<Long, IntegrationKeyScope> out = new HashMap<>();
        for (ApiKey key : keys) {
            out.put(key.getId(), scopeOf(key, categoriesByKey.getOrDefault(key.getId(), Set.of())));
        }
        return out;
    }

    /**
     * Turns what an administrator ticked into a scope, refusing anything that is not a real
     * choice: an unknown category id, or a status the API does not expose. Nothing ticked in a
     * dimension is «no restriction» in that dimension.
     */
    @Transactional(readOnly = true)
    public IntegrationKeyScope parse(Collection<Long> categoryIds, Collection<String> statusNames) {
        Set<Long> categories = categoryIds == null ? Set.of()
                : categoryIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!categories.isEmpty()) {
            Set<Long> known = categoryRepository.findAllById(categories).stream()
                    .map(LogSheetCategory::getId).collect(Collectors.toSet());
            if (!known.containsAll(categories)) {
                throw new IllegalArgumentException("Log sheet category not found.");
            }
        }
        Set<LogSheetStatus> statuses = new LinkedHashSet<>();
        if (statusNames != null) {
            for (String name : statusNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                LogSheetStatus status = LogSheetStatus.fromNullable(name.trim());
                if (status == null || !IntegrationLogSheetQuery.EXPOSABLE_STATUSES.contains(status)) {
                    throw new IllegalArgumentException("Invalid API key status scope.");
                }
                statuses.add(status);
            }
        }
        return IntegrationKeyScope.of(categories, statuses);
    }

    /**
     * Makes {@code scope} the key's scope. Categories are written by difference, row by row: a
     * flush runs inserts before deletes, so delete-all-then-insert would collide on every
     * category kept (AGENTS.md #192), and {@code saveAll}/{@code deleteAll} would leave no audit
     * row (#193). The status column rides on the key's own save, which is audited.
     *
     * <p>Package-level callers only, inside their transaction: {@link ApiKeyService} owns when a
     * scope may change.
     */
    void write(ApiKey key, IntegrationKeyScope scope) {
        key.setScopeStatuses(scope.statusesColumn());
        apiKeyRepository.save(key);

        Set<Long> wanted = scope.categoryIds() == null ? Set.of() : scope.categoryIds();
        List<ApiKeyCategory> current = apiKeyCategoryRepository.findByApiKeyId(key.getId());
        Set<Long> kept = new HashSet<>();
        for (ApiKeyCategory row : current) {
            if (wanted.contains(row.getCategoryId())) {
                kept.add(row.getCategoryId());
            } else {
                apiKeyCategoryRepository.delete(row);
            }
        }
        for (Long categoryId : wanted) {
            if (!kept.contains(categoryId)) {
                apiKeyCategoryRepository.save(new ApiKeyCategory(key.getId(), categoryId));
            }
        }
    }

    private static IntegrationKeyScope scopeOf(ApiKey key, Set<Long> categoryIds) {
        return new IntegrationKeyScope(
                categoryIds.isEmpty() ? null : categoryIds,
                IntegrationKeyScope.parseStatusesColumn(key.getScopeStatuses()));
    }

    private static Set<Long> categoryIdsOf(List<ApiKeyCategory> rows) {
        return rows.stream().map(ApiKeyCategory::getCategoryId).collect(Collectors.toSet());
    }
}
