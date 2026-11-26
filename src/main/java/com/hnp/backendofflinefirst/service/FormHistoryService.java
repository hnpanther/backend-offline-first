package com.hnp.backendofflinefirst.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.dto.FormHistoryPage;
import com.hnp.backendofflinefirst.dto.FormSubmissionDto;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;

@Service
@RequiredArgsConstructor
public class FormHistoryService {
    private final FormSubmissionRepository submissionRepository;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public FormHistoryPage historyPage(Long userId, long after, Long through, long since, int size) {
        if (after < 0 || since < 0 || size < 1 || size > 100 || (through != null && through < after)) {
            throw new IllegalArgumentException("Invalid history cursor or page size.");
        }
        long head = submissionRepository.historyHead(userId);
        long end = through == null ? head : Math.min(head, through);
        if (end < after) throw new IllegalArgumentException("Invalid history cursor or page size.");
        var rows = submissionRepository.historyPage(userId, after, end, since, PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        var page = hasMore ? rows.subList(0, size) : rows;
        long next = hasMore ? page.getLast().getHistoryPosition() : end;
        return new FormHistoryPage(userId, next, end, hasMore, page.stream().map(FormSubmissionDto::from).toList());
    }

    /** Older tablets expect a single newest-first JSON array. Bound memory across the whole response. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void writeLegacyHistory(Long userId, OutputStream output) throws IOException {
        var limit = PageRequest.of(0, 50);
        long beforeTime = Long.MAX_VALUE;
        long beforeId = Long.MAX_VALUE;
        try (var writer = mapper.writerFor(FormSubmissionDto.class).writeValuesAsArray(output)) {
            while (true) {
                var rows = submissionRepository.legacyHistoryPage(userId, beforeTime, beforeId, limit);
                for (var row : rows) {
                    writer.write(FormSubmissionDto.from(row));
                    entityManager.detach(row);
                }
                if (rows.size() < limit.getPageSize()) return;
                var last = rows.getLast();
                beforeTime = last.getUpdatedAt();
                beforeId = last.getId();
            }
        }
    }
}
