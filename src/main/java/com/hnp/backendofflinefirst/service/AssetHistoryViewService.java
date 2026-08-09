package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AssetActivationChangeType;
import com.hnp.backendofflinefirst.domain.AssetStatusChangeType;
import com.hnp.backendofflinefirst.domain.AssetStatusSource;
import com.hnp.backendofflinefirst.entity.AssetActivationHistory;
import com.hnp.backendofflinefirst.entity.AssetStatusHistory;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AssetStatusHistoryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds one readable timeline for an asset out of two deliberately separate journals.
 *
 * <h2>Why the merge lives here and not in the database</h2>
 * {@code asset_status_history} and {@code asset_activation_history} are separate tables on
 * purpose — see {@link AssetActivationHistoryService} for why mixing them would put activation
 * rows in front of the log-sheet reversal lookup. A UNION view would quietly re-create that
 * coupling in SQL. Merging in Java keeps the storage decision intact: this class reads both and
 * interleaves them for display, and nothing about the display can reach back into behaviour.
 *
 * <h2>Cost</h2>
 * Two indexed reads plus one lookup each for the users and sheets mentioned. Names are resolved
 * in a batch rather than per row, because a busy asset's timeline is dominated by a handful of
 * repeat actors and the same sheet appearing twice (applied, then reverted).
 */
@Service
@RequiredArgsConstructor
public class AssetHistoryViewService {

    private final AssetStatusHistoryRepository statusHistoryRepository;
    private final AssetActivationHistoryService activationHistoryService;
    private final UserRepository userRepository;
    private final LogSheetRepository logSheetRepository;

    /** What kind of event a row is — drives the icon and colour, and the page's filter. */
    public enum EventKind {
        STATUS,
        ACTIVATION
    }

    /**
     * One line of the timeline.
     *
     * @param logSheetId the sheet that caused it, or null for a manual edit or an activation
     * @param actorName  resolved display name, or null when the change had no signed-in actor
     */
    public record HistoryEvent(EventKind kind,
                               long changedAt,
                               String oldValue,
                               String newValue,
                               String changeType,
                               AssetStatusSource source,
                               Long logSheetId,
                               String logSheetTitle,
                               String fieldKey,
                               Long actorUserId,
                               String actorName,
                               boolean reverted,
                               /** The approval decision behind this change, when there was one. */
                               Long requestId) {

        /** True when a log sheet drove this — the case that gets a link to the sheet. */
        public boolean fromLogSheet() {
            return kind == EventKind.STATUS && source == AssetStatusSource.LOG_SHEET;
        }

        public boolean isStatus() {
            return kind == EventKind.STATUS;
        }
    }

    /**
     * The asset's whole timeline, newest first.
     *
     * <p>Status rows are capped by {@code statusLimit}; activation rows are not, because they
     * are only written when someone deliberately switches an asset on or off — a handful over
     * an asset's life, against potentially one status row per completed sheet.
     */
    public List<HistoryEvent> timeline(Long assetId, int statusLimit) {
        if (assetId == null) {
            return List.of();
        }

        List<AssetStatusHistory> statusRows = statusHistoryRepository
                .findByAssetIdOrderByChangedAtDescIdDesc(assetId, Pageable.ofSize(Math.max(statusLimit, 1)))
                .getContent();
        List<AssetActivationHistory> activationRows = activationHistoryService.forAsset(assetId);

        Map<Long, String> userNames = resolveUserNames(statusRows, activationRows);
        Map<Long, String> sheetTitles = resolveSheetTitles(statusRows);

        List<HistoryEvent> events = new ArrayList<>(statusRows.size() + activationRows.size());
        for (AssetStatusHistory row : statusRows) {
            events.add(new HistoryEvent(
                    EventKind.STATUS,
                    row.getChangedAt() != null ? row.getChangedAt() : 0L,
                    row.getOldStatus(),
                    row.getNewStatus(),
                    row.getChangeType() != null ? row.getChangeType().name() : null,
                    row.getSource(),
                    row.getLogSheetId(),
                    nameOf(sheetTitles, row.getLogSheetId()),
                    row.getFieldKey(),
                    row.getActorUserId(),
                    nameOf(userNames, row.getActorUserId()),
                    row.getRevertedAt() != null,
                    row.getRequestId()));
        }
        for (AssetActivationHistory row : activationRows) {
            boolean nowActive = row.isActive();
            events.add(new HistoryEvent(
                    EventKind.ACTIVATION,
                    row.getChangedAt() != null ? row.getChangedAt() : 0L,
                    describeActive(row.getWasActive()),
                    describeActive(nowActive),
                    row.getChangeType() != null ? row.getChangeType().name() : null,
                    null,
                    null,
                    null,
                    null,
                    row.getActorUserId(),
                    nameOf(userNames, row.getActorUserId()),
                    false,
                    null));
        }

        // Newest first. Status and activation rows written in the same millisecond are ordered
        // status-last so an "activated" event reads before the reading that followed it.
        events.sort(Comparator.comparingLong(HistoryEvent::changedAt).reversed()
                .thenComparing(e -> e.kind() == EventKind.STATUS ? 0 : 1));
        return events;
    }

    /** Whether anything at all has been recorded — lets the page show a real empty state. */
    public boolean hasAnyHistory(Long assetId) {
        return !timeline(assetId, 1).isEmpty();
    }

    /**
     * Null-safe lookup. {@code Map.of()} is immutable and its {@code get(null)} <b>throws</b>,
     * so a row with no actor — an Excel import runs on its own executor with no security
     * context, and legacy rows predate the column — would take the whole page down. Guarding
     * here rather than at each call site means a future field cannot reintroduce it.
     */
    private static String nameOf(Map<Long, String> names, Long id) {
        return id == null ? null : names.get(id);
    }

    private static String describeActive(Boolean active) {
        if (active == null) {
            return null;
        }
        return active ? "فعال" : "غیرفعال";
    }

    private Map<Long, String> resolveUserNames(List<AssetStatusHistory> statusRows,
                                               List<AssetActivationHistory> activationRows) {
        Set<Long> ids = new LinkedHashSet<>();
        statusRows.stream().map(AssetStatusHistory::getActorUserId).filter(Objects::nonNull).forEach(ids::add);
        activationRows.stream().map(AssetActivationHistory::getActorUserId).filter(Objects::nonNull).forEach(ids::add);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, AssetHistoryViewService::displayName, (a, b) -> a));
    }

    private Map<Long, String> resolveSheetTitles(List<AssetStatusHistory> statusRows) {
        Set<Long> ids = statusRows.stream()
                .map(AssetStatusHistory::getLogSheetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return logSheetRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(LogSheet::getId, AssetHistoryViewService::sheetTitle, (a, b) -> a));
    }

    private static String displayName(User u) {
        return u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername();
    }

    private static String sheetTitle(LogSheet s) {
        return s.getTemplateName() != null && !s.getTemplateName().isBlank()
                ? s.getTemplateName()
                : ("لاگ‌شیت #" + s.getId());
    }

    /** How many rows of one kind the timeline holds — the page's summary chips. */
    public static long countOf(List<HistoryEvent> events, EventKind kind) {
        return events.stream().filter(e -> e.kind() == kind).count();
    }
}
