package com.hnp.backendofflinefirst.dto;

import java.util.List;

public record FormHistoryPage(long ownerId, long next, long through, boolean hasMore, List<FormSubmissionDto> items) {}
