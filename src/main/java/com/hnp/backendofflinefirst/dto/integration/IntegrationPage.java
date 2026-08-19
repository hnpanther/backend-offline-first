package com.hnp.backendofflinefirst.dto.integration;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The page envelope of the integration API.
 *
 * <p>Its own shape rather than Spring Data's serialised {@code Page}. Boot serialises a
 * {@code Page} as a large object with {@code pageable}, {@code sort}, {@code first},
 * {@code last}, {@code numberOfElements} and an {@code empty} flag — a structure that is a
 * detail of the framework this application happens to use, that changed shape between Boot
 * versions (and warns about exactly that on every serialisation), and that an integrator would
 * have to write a parser for. Six fields they can rely on is the contract worth publishing.
 *
 * @param items         the rows on this page
 * @param page          zero-based page index, echoed back so a caller can verify what it got
 * @param size          rows per page actually applied — <b>not</b> what was asked for, if the
 *                      request exceeded the maximum. Echoing the effective value is what lets
 *                      a caller notice it is being clamped instead of silently walking a page
 *                      size that does not exist.
 * @param totalElements rows matching the filter across every page
 * @param totalPages    number of pages at this size
 * @param hasNext       whether another page exists — so the caller's loop needs no arithmetic
 */
public record IntegrationPage<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static <E, T> IntegrationPage<T> of(Page<E> source, Function<E, T> mapper) {
        return new IntegrationPage<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.hasNext());
    }
}
