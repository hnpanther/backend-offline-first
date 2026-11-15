package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.LookupColumn;
import com.hnp.backendofflinefirst.entity.LookupDownload;
import com.hnp.backendofflinefirst.entity.LookupItem;
import com.hnp.backendofflinefirst.entity.LookupList;
import com.hnp.backendofflinefirst.repository.LookupDownloadRepository;
import com.hnp.backendofflinefirst.repository.LookupItemRepository;
import com.hnp.backendofflinefirst.repository.LookupListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A reference list's items for a device (roadmap §31 Phase 2 — «How the lists reach the tablet»
 * and «the device lease»). Behind {@code GET /api/lookups/{code}/items}.
 *
 * <h2>Four rules, each a layer of the design</h2>
 *
 * <ol>
 *   <li><b>The grant.</b> Only a list an open round assigned to the caller draws from
 *       ({@link LookupGrantService#userMayHoldList}). Anything else is refused as if forbidden,
 *       whether or not the list exists — the answer must not tell a caller which list codes are
 *       real.</li>
 *   <li><b>The columns.</b> Code, label, active, and only the attributes of columns an
 *       administrator flagged {@code offline}. Built here, on the server: the client is never
 *       trusted to drop a national id it was sent.</li>
 *   <li><b>The cursor.</b> Half-open over {@code (updated_at, id)}, strictly after it, so pages
 *       neither overlap nor leave a gap even when an import wrote thousands of rows at one
 *       instant. Deactivation is an update and arrives the same way; no tombstone feed.</li>
 *   <li><b>The lease.</b> Every page says {@code leaseUntil} — now plus the list's
 *       {@code device_lease_hours} — and the server time, so the device can tell a clock set back
 *       from a lease still running. The device deletes its copy when the lease ends.</li>
 * </ol>
 *
 * <p>And a fifth that is not a rule but a record: every page writes a {@link LookupDownload}.
 *
 * <p><b>The revision is read before the page.</b> A change committed between the two would then be
 * in the page and newer than the revision the device records — so the device fetches once more,
 * harmlessly. Read after, the same change could be missing from a page stamped with a revision that
 * claims it, and the device would believe itself current while it is not.
 */
@Service
@RequiredArgsConstructor
public class LookupDeviceService {

    public static final int DEFAULT_PAGE = 500;
    public static final int MAX_PAGE = 1000;

    private final LookupListRepository listRepository;
    private final LookupItemRepository itemRepository;
    private final LookupDownloadRepository downloadRepository;
    private final LookupGrantService grantService;

    public record DeviceColumn(String key, String label, boolean searchable, boolean display) {}

    public record DeviceItem(long id, String code, String label, boolean active,
                             Map<String, String> attributes, long updatedAt) {}

    public record DevicePage(String code, String name, long revision, long serverTime, long leaseUntil,
                             List<DeviceColumn> columns, List<DeviceItem> items,
                             boolean hasMore, long nextSince, long nextAfterId) {}

    /**
     * @throws AccessDeniedException when the caller may not hold the list — including when it
     *         does not exist, so a refusal says nothing about which codes are real
     */
    @Transactional
    public DevicePage page(Long userId, String sessionJti, String listCode,
                           long since, long afterId, Integer requestedLimit) {
        if (!grantService.userMayHoldList(userId, listCode)) {
            throw new AccessDeniedException("This reference list is not needed by any round assigned to you.");
        }
        LookupList list = listRepository.findByCodeIgnoreCase(listCode.trim())
                .orElseThrow(() -> new AccessDeniedException(
                        "This reference list is not needed by any round assigned to you."));
        int limit = requestedLimit == null ? DEFAULT_PAGE : Math.max(1, Math.min(MAX_PAGE, requestedLimit));
        long safeSince = Math.max(0, since);
        long safeAfter = Math.max(0, afterId);

        long revision = listRepository.currentRevision(list.getId());
        List<LookupItem> rows = itemRepository.pageForDevice(list.getId(), safeSince, safeAfter,
                PageRequest.of(0, limit + 1));
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }

        List<LookupColumn> offline = new ArrayList<>();
        List<DeviceColumn> columns = new ArrayList<>();
        for (LookupColumn column : list.getColumns() == null ? List.<LookupColumn>of() : list.getColumns()) {
            if (column.isOffline()) {
                offline.add(column);
                columns.add(new DeviceColumn(column.getKey(), column.getLabel(),
                        column.isSearchable(), column.isDisplay()));
            }
        }

        List<DeviceItem> items = new ArrayList<>(rows.size());
        for (LookupItem item : rows) {
            Map<String, String> attributes = new LinkedHashMap<>();
            if (item.getAttributes() != null) {
                for (LookupColumn column : offline) {
                    String value = item.getAttributes().get(column.getKey());
                    if (value != null && !value.isBlank()) {
                        attributes.put(column.getKey(), value);
                    }
                }
            }
            items.add(new DeviceItem(item.getId(), item.getCode(), item.getLabel(), item.isActive(),
                    attributes, item.getUpdatedAt()));
        }

        long now = System.currentTimeMillis();
        LookupItem last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        long nextSince = last == null ? safeSince : last.getUpdatedAt();
        long nextAfter = last == null ? safeAfter : last.getId();

        LookupDownload download = new LookupDownload();
        download.setListId(list.getId());
        download.setUserId(userId);
        download.setSessionJti(sessionJti == null || sessionJti.length() > 64 ? null : sessionJti);
        download.setRevision(revision);
        download.setItemCount(items.size());
        download.setSinceUpdatedAt(safeSince);
        download.setSinceId(safeAfter);
        download.setFetchedAt(now);
        downloadRepository.save(download);

        long leaseUntil = now + list.getDeviceLeaseHours() * 3_600_000L;
        return new DevicePage(list.getCode(), list.getName(), revision, now, leaseUntil, columns, items,
                hasMore, nextSince, nextAfter);
    }
}
