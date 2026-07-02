package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetVoidSubmission;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Completion of server-generated log sheets, from either the offline mobile app
 * (batch sync) or the server web UI. Core rules:
 * <ul>
 *   <li>only the current assignee may complete a sheet;</li>
 *   <li>the deadline is judged against the device completion time ({@code completedAt}),
 *       never the sync time — so work finished within the window offline is always
 *       accepted even if synced much later;</li>
 *   <li>a submission from anyone who is no longer the assignee (e.g. after a
 *       supervisor takeover) is stored but flagged {@code SUPERSEDED} and does not
 *       overwrite the authoritative sheet;</li>
 *   <li>replayed offline submits are idempotent via {@code clientActionId}.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LogSheetService {

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final LogSheetVoidSubmissionRepository voidSubmissionRepository;
    private final LogSheetActionLogger actionLogger;
    private final OperationalUnitScopeService scopeService;

    // ---------------------------------------------------------------- mobile sync

    @Transactional
    public List<LogSheetSubmitResult> submitBatch(List<LogSheetDto> dtos) {
        List<LogSheetSubmitResult> results = new ArrayList<>();
        if (dtos == null) return results;
        for (LogSheetDto dto : dtos) {
            try {
                results.add(submitOne(dto));
            } catch (Exception e) {
                results.add(new LogSheetSubmitResult(dto.getLocalId(), dto.getServerId(), e.getMessage()));
            }
        }
        return results;
    }

    private LogSheetSubmitResult submitOne(LogSheetDto dto) {
        if (actionLogger.isReplay(dto.getClientActionId())) {
            return new LogSheetSubmitResult(dto.getLocalId(), dto.getServerId(), null, "DUPLICATE");
        }

        Long serverId = dto.getServerId() != null ? dto.getServerId() : dto.getId();
        if (serverId == null) {
            throw new IllegalArgumentException("شناسه سروری لاگ‌شیت ارسال نشده است.");
        }
        LogSheet sheet = logSheetRepository.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("لاگ‌شیت روی سرور یافت نشد."));

        Long currentUserId = SecurityUtils.currentUserId();
        long now = System.currentTimeMillis();
        long completedAt = firstNonNull(dto.getCompletedAt(), dto.getSubmittedAt(), now);

        // Already completed: idempotent for the completer, otherwise a superseded late sync.
        if (sheet.getStatus() == LogSheetStatus.SUBMITTED) {
            if (currentUserId != null && currentUserId.equals(sheet.getCompletedByUserId())) {
                return new LogSheetSubmitResult(dto.getLocalId(), serverId, null, "DUPLICATE");
            }
            return voidSubmission(sheet, dto, currentUserId, completedAt, now,
                    "این لاگ‌شیت قبلاً توسط شخص دیگری تکمیل شده است.");
        }

        // A submission from someone who is not the current assignee is voided
        // (covers supervisor takeover while the operator was offline).
        if (SecurityUtils.isUnitScopedOnly() && !currentUserId.equals(sheet.getAssigneeUserId())) {
            return voidSubmission(sheet, dto, currentUserId, completedAt, now,
                    "این لاگ‌شیت دیگر به شما تخصیص ندارد؛ توسط شخص دیگری در حال انجام یا تکمیل است.");
        }

        if (sheet.getStatus() == LogSheetStatus.EXPIRED) {
            throw new IllegalStateException("مهلت تکمیل این لاگ‌شیت به پایان رسیده است.");
        }
        // Deadline judged on device completion time, not the (possibly late) sync time.
        if (sheet.getDueAt() != null && completedAt > sheet.getDueAt()) {
            sheet.setStatus(LogSheetStatus.EXPIRED);
            sheet.setExpiredAt(now);
            sheet.setUpdatedAt(now);
            logSheetRepository.save(sheet);
            actionLogger.record(serverId, LogSheetActionType.EXPIRE, ActionSource.MOBILE,
                    currentUserId, sheet.getAssigneeUserId(), null, completedAt, null);
            return new LogSheetSubmitResult(dto.getLocalId(), serverId,
                    "مهلت تکمیل این لاگ‌شیت به پایان رسیده است.", "EXPIRED");
        }

        replaceEntries(serverId, dto.getEntries());
        applyCompletion(sheet, currentUserId, completedAt, firstNonNull(dto.getSubmittedAt(), completedAt),
                now, dto.getOperatorName(), dto.getSyncStatus(), ActionSource.MOBILE, dto.getClientActionId());
        return new LogSheetSubmitResult(dto.getLocalId(), serverId, null, "SUBMITTED");
    }

    // ---------------------------------------------------------------- web completion

    /**
     * Completes a sheet from the server web UI (supervisor or the assigned operator).
     * A supervisor may complete a sheet in their unit even if it is assigned to
     * someone else (they are effectively taking responsibility for it).
     */
    @Transactional
    public LogSheet completeFromWeb(Long sheetId, Map<String, Map<String, Object>> entryValues) {
        LogSheet sheet = logSheetRepository.findById(sheetId)
                .orElseThrow(() -> new IllegalArgumentException("لاگ‌شیت یافت نشد."));
        Long userId = SecurityUtils.currentUserId();

        boolean isSupervisor = scopeService.isSupervisorOf(userId, sheet.getOperationalUnitId());
        boolean isAssignee = userId != null && userId.equals(sheet.getAssigneeUserId());
        if (SecurityUtils.isUnitScopedOnly() && !isSupervisor && !isAssignee) {
            throw new AccessDeniedException("اجازه تکمیل این لاگ‌شیت را ندارید.");
        }
        if (sheet.getStatus() == LogSheetStatus.SUBMITTED) {
            throw new IllegalStateException("این لاگ‌شیت قبلاً تکمیل شده است.");
        }

        long now = System.currentTimeMillis();
        if (sheet.getDueAt() != null && now > sheet.getDueAt()) {
            throw new IllegalStateException("مهلت تکمیل این لاگ‌شیت به پایان رسیده است.");
        }

        applyWebEntryValues(sheetId, entryValues);
        applyCompletion(sheet, userId, now, now, now, null, null, ActionSource.WEB, null);
        return sheet;
    }

    // ---------------------------------------------------------------- shared helpers

    private void applyCompletion(LogSheet sheet, Long actorUserId, long completedAt, long submittedAt,
                                 long syncedAt, String operatorName, String syncStatus,
                                 ActionSource source, String clientActionId) {
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setCompletedByUserId(actorUserId);
        sheet.setCompletedAt(completedAt);
        sheet.setSubmittedAt(submittedAt);
        sheet.setSyncedAt(syncedAt);
        if (syncStatus != null) sheet.setSyncStatus(syncStatus);
        if (operatorName != null) sheet.setOperatorName(operatorName);
        sheet.setUpdatedAt(syncedAt);
        logSheetRepository.save(sheet);

        actionLogger.record(sheet.getId(), LogSheetActionType.COMPLETE, source,
                actorUserId, null, null, completedAt, clientActionId);
        actionLogger.record(sheet.getId(), LogSheetActionType.SUBMIT, source,
                actorUserId, null, null, syncedAt, null);
    }

    /** Records a late/void submission that must not overwrite the completed sheet. */
    private LogSheetSubmitResult voidSubmission(LogSheet sheet, LogSheetDto dto, Long userId,
                                                long completedAt, long now, String reason) {
        LogSheetVoidSubmission v = new LogSheetVoidSubmission();
        v.setLogSheetId(sheet.getId());
        v.setSubmittedByUserId(userId);
        v.setCompletedAt(completedAt);
        v.setSyncedAt(now);
        v.setReason(reason);
        v.setPayload(entriesToPayload(dto.getEntries()));
        voidSubmissionRepository.save(v);

        actionLogger.record(sheet.getId(), LogSheetActionType.SUPERSEDE, ActionSource.MOBILE,
                userId, null, null, completedAt, dto.getClientActionId());
        return new LogSheetSubmitResult(dto.getLocalId(), sheet.getId(), reason, "SUPERSEDED");
    }

    private void applyWebEntryValues(Long logSheetId, Map<String, Map<String, Object>> entryValues) {
        if (entryValues == null || entryValues.isEmpty()) return;
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(logSheetId);
        for (LogSheetEntry entry : entries) {
            Map<String, Object> values = entryValues.get(String.valueOf(entry.getId()));
            if (values != null) {
                entry.setFormData(values);
                logSheetEntryRepository.save(entry);
            }
        }
    }

    private void replaceEntries(Long logSheetId, List<LogSheetEntryDto> entryDtos) {
        if (entryDtos == null) return; // keep pre-populated entries untouched
        logSheetEntryRepository.deleteAll(logSheetEntryRepository.findByLogSheetId(logSheetId));
        for (LogSheetEntryDto dto : entryDtos) {
            LogSheetEntry entry = new LogSheetEntry();
            entry.setLogSheetId(logSheetId);
            entry.setAssetId(dto.getAssetId());
            entry.setAssetName(dto.getAssetName());
            entry.setSubFunctionCode(dto.getSubFunctionCode());
            entry.setSubFunctionTag(dto.getSubFunctionTag());
            entry.setClassId(dto.getClassId());
            entry.setFormData(dto.getFormData());
            logSheetEntryRepository.save(entry);
        }
    }

    private List<Map<String, Object>> entriesToPayload(List<LogSheetEntryDto> entries) {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (entries == null) return payload;
        for (LogSheetEntryDto e : entries) {
            Map<String, Object> m = new HashMap<>();
            m.put("assetId", e.getAssetId());
            m.put("assetName", e.getAssetName());
            m.put("formData", e.getFormData());
            payload.add(m);
        }
        return payload;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }
}
