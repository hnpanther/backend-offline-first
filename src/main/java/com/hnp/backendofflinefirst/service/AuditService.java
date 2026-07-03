package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.audit.AuditEntitySupport;
import com.hnp.backendofflinefirst.audit.AuditFieldChange;
import com.hnp.backendofflinefirst.domain.AuditAction;
import com.hnp.backendofflinefirst.entity.AuditLog;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.logging.RequestMdcFilter;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Persists field-level audit rows asynchronously so request threads are not blocked on extra INSERTs.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditWriteService auditWriteService;
    private final BusinessEventLogger businessEventLogger;

    @Value("${app.audit.enabled:true}")
    private boolean enabled;

    public void recordChange(Object oldEntity, Object newEntity, AuditAction action) {
        if (!enabled) {
            return;
        }
        Object sample = newEntity != null ? newEntity : oldEntity;
        if (!AuditEntitySupport.shouldAudit(sample)) {
            return;
        }

        List<AuditFieldChange> changes = AuditEntitySupport.diff(oldEntity, newEntity, action);
        if (action == AuditAction.UPDATE && changes.isEmpty()) {
            return;
        }

        AuditLog row = new AuditLog();
        row.setEntityType(AuditEntitySupport.entityType(sample));
        row.setEntityId(AuditEntitySupport.entityId(sample));
        row.setAction(action);
        row.setActorUserId(SecurityUtils.currentUserId());
        row.setActorUsername(currentUsername());
        row.setSource(resolveSource());
        row.setRequestId(MDC.get(RequestMdcFilter.MDC_CORRELATION));
        row.setChanges(changes.stream().map(AuditFieldChange::toMap).collect(Collectors.toList()));
        row.setRecordedAt(System.currentTimeMillis());

        persistAsync(row);
        businessEventLogger.auditPersisted(row);
    }

    private void persistAsync(AuditLog row) {
        auditWriteService.save(row);
    }

    private static String currentUsername() {
        AppUserDetails user = SecurityUtils.currentUser();
        return user != null ? user.getUsername() : "system";
    }

    private static String resolveSource() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "SERVER";
            }
            HttpServletRequest req = attrs.getRequest();
            return req.getRequestURI() != null && req.getRequestURI().startsWith("/api/") ? "API" : "WEB";
        } catch (Exception e) {
            return "SERVER";
        }
    }
}
