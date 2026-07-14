package com.hnp.backendofflinefirst.ui;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.ui.Model;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Shared pagination and search helpers for admin list pages.
 */
public final class WebListSupport {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;

    private WebListSupport() {}

    public static Pageable pageable(int page, Integer size) {
        int s = size != null && size > 0 ? Math.min(size, MAX_SIZE) : DEFAULT_SIZE;
        return PageRequest.of(Math.max(0, page), s, Sort.by(Sort.Direction.DESC, "id"));
    }

    /** Page request without sort — for custom @Query methods that define their own ORDER BY. */
    public static Pageable unsortedPageable(int page, Integer size) {
        int s = size != null && size > 0 ? Math.min(size, MAX_SIZE) : DEFAULT_SIZE;
        return PageRequest.of(Math.max(0, page), s);
    }

    public static String normalizeQuery(String q) {
        if (q == null) return "";
        return q.trim();
    }

    public static void addPagination(Model model, Page<?> page, String q, int pageNum, int size) {
        model.addAttribute("listPage", page);
        model.addAttribute("q", normalizeQuery(q));
        model.addAttribute("pageNumber", pageNum);
        model.addAttribute("pageSize", size > 0 ? size : DEFAULT_SIZE);
        model.addAttribute("totalPages", page.getTotalPages());
        model.addAttribute("totalElements", page.getTotalElements());
    }

    /** Null-safe search term for repository queries (empty string matches all). */
    public static String searchTerm(String q) {
        String normalized = normalizeQuery(q);
        return normalized.isEmpty() ? null : normalized;
    }

    public static boolean hasSearch(String q) {
        return searchTerm(q) != null;
    }

    public static <T> Page<T> pagedList(String q, Pageable pageable,
                                        Function<Pageable, Page<T>> all,
                                        BiFunction<String, Pageable, Page<T>> search) {
        String term = searchTerm(q);
        return term == null ? all.apply(pageable) : search.apply(term, pageable);
    }
}
