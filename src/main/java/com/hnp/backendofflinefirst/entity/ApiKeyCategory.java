package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One log-sheet category an integration API key is limited to (V20, {@code api_key_categories}).
 *
 * <p>A plain row rather than a collection on {@link ApiKey}: no entity in this codebase declares a
 * JPA association (AGENTS.md #192). {@code ApiKeyScopeService} reads these beside the key.
 *
 * <p>Written row by row, never with {@code saveAll}: widening or narrowing what a deployed
 * consumer receives is exactly the kind of decision the audit trail is for (AGENTS.md #193).
 */
@Entity
@Table(name = "api_key_categories")
@IdClass(ApiKeyCategoryId.class)
@Data
@NoArgsConstructor
public class ApiKeyCategory {

    @Id
    @Column(name = "api_key_id")
    private Long apiKeyId;

    @Id
    @Column(name = "category_id")
    private Long categoryId;

    public ApiKeyCategory(Long apiKeyId, Long categoryId) {
        this.apiKeyId = apiKeyId;
        this.categoryId = categoryId;
    }
}
