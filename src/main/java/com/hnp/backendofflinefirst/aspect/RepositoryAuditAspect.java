package com.hnp.backendofflinefirst.aspect;

import com.hnp.backendofflinefirst.audit.AuditEntitySupport;
import com.hnp.backendofflinefirst.domain.AuditAction;
import com.hnp.backendofflinefirst.service.AuditService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Hibernate;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Captures CREATE/UPDATE/DELETE on JPA entities at the repository layer.
 * <p>
 * For UPDATE, old field values are copied into an independent map before {@code save}
 * proceeds. Using {@code entityManager.find} alone is unsafe: the persistence context
 * returns the already-mutated managed instance, so diffs look empty and audit rows are skipped.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RepositoryAuditAspect {

    private final AuditService auditService;

    @PersistenceContext
    private EntityManager entityManager;

    @Around("execution(* org.springframework.data.repository.CrudRepository+.save(..))")
    public Object auditSave(ProceedingJoinPoint pjp) throws Throwable {
        return auditSaveInternal(pjp);
    }

    /**
     * {@code saveAndFlush} calls {@code save} via self-invocation, so the {@code save}
     * advice never runs; this advice covers that entry point.
     */
    @Around("execution(* org.springframework.data.jpa.repository.JpaRepository+.saveAndFlush(..))")
    public Object auditSaveAndFlush(ProceedingJoinPoint pjp) throws Throwable {
        return auditSaveInternal(pjp);
    }

    private Object auditSaveInternal(ProceedingJoinPoint pjp) throws Throwable {
        Object entity = pjp.getArgs()[0];
        if (!AuditEntitySupport.shouldAudit(entity)) {
            return pjp.proceed();
        }

        Map<String, Object> oldSnapshot = captureIndependentOldValues(entity);
        AuditAction action = oldSnapshot == null ? AuditAction.CREATE : AuditAction.UPDATE;

        Object result = pjp.proceed();
        Object saved = result != null ? result : entity;
        auditService.recordChange(oldSnapshot, saved, action);
        return result;
    }

    @Around("execution(* org.springframework.data.repository.CrudRepository+.delete(..))")
    public Object auditDelete(ProceedingJoinPoint pjp) throws Throwable {
        Object entity = pjp.getArgs()[0];
        if (AuditEntitySupport.shouldAudit(entity)) {
            // Diff runs synchronously inside recordChange before delete proceeds.
            auditService.recordChange(entity, null, AuditAction.DELETE);
        }
        return pjp.proceed();
    }

    @Around("execution(* org.springframework.data.repository.CrudRepository+.deleteById(..))")
    public Object auditDeleteById(ProceedingJoinPoint pjp) throws Throwable {
        Object idArg = pjp.getArgs()[0];
        Object entity = resolveEntityForDelete(pjp, idArg);
        if (entity != null && AuditEntitySupport.shouldAudit(entity)) {
            auditService.recordChange(entity, null, AuditAction.DELETE);
        }
        return pjp.proceed();
    }

    /**
     * Returns a detached field map of pre-change values, or {@code null} for CREATE.
     * <p>
     * Prefer a short-lived EntityManager (committed DB state, outside this persistence
     * context). Hibernate {@code loadedState} is only a fallback: after validation queries
     * trigger auto-flush, loadedState is already updated to the new values and cannot be
     * used to detect the change.
     */
    Map<String, Object> captureIndependentOldValues(Object entity) {
        Class<?> type = Hibernate.getClass(entity);
        Object persistenceId = resolvePersistenceId(entity, type);
        if (persistenceId == null) {
            return null;
        }

        Map<String, Object> committed = snapshotFromSeparateEntityManager(type, persistenceId);
        if (committed != null) {
            return committed;
        }

        // Row not visible outside this TX (e.g. created earlier in the same transaction).
        return snapshotFromHibernateLoadedState(entity);
    }

    private Map<String, Object> snapshotFromHibernateLoadedState(Object entity) {
        try {
            SessionImplementor session = entityManager.unwrap(SessionImplementor.class);
            Object unproxied = Hibernate.unproxy(entity);
            EntityEntry entry = session.getPersistenceContextInternal().getEntry(unproxied);
            if (entry == null || entry.getLoadedState() == null) {
                return null;
            }
            EntityPersister persister = entry.getPersister();
            return AuditEntitySupport.captureFromLoadedState(
                    unproxied,
                    persister.getPropertyNames(),
                    entry.getLoadedState());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Map<String, Object> snapshotFromSeparateEntityManager(Class<?> type, Object persistenceId) {
        EntityManagerFactory emf = entityManager.getEntityManagerFactory();
        EntityManager freshEm = emf.createEntityManager();
        try {
            Object fresh = freshEm.find(type, persistenceId);
            if (fresh == null) {
                return null;
            }
            return AuditEntitySupport.captureFieldValues(fresh);
        } finally {
            if (freshEm.isOpen()) {
                freshEm.close();
            }
        }
    }

    private Object resolvePersistenceId(Object entity, Class<?> entityType) {
        if (entityType.getAnnotation(IdClass.class) != null) {
            return buildCompositeId(entity, entityType);
        }
        for (Field field : entityType.getDeclaredFields()) {
            if (!field.isAnnotationPresent(Id.class)) {
                continue;
            }
            field.setAccessible(true);
            try {
                return field.get(entity);
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private Object buildCompositeId(Object entity, Class<?> entityType) {
        IdClass idClassAnn = entityType.getAnnotation(IdClass.class);
        if (idClassAnn == null) {
            return null;
        }
        try {
            Class<?> idClass = idClassAnn.value();
            Object id = idClass.getDeclaredConstructor().newInstance();
            boolean anyNull = false;
            for (Field entityField : entityType.getDeclaredFields()) {
                if (!entityField.isAnnotationPresent(Id.class)) {
                    continue;
                }
                entityField.setAccessible(true);
                Object part = entityField.get(entity);
                if (part == null) {
                    anyNull = true;
                }
                Field idField = idClass.getDeclaredField(entityField.getName());
                idField.setAccessible(true);
                idField.set(id, part);
            }
            return anyNull ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    private Object resolveEntityForDelete(ProceedingJoinPoint pjp, Object idArg) {
        try {
            Class<?>[] interfaces = pjp.getTarget().getClass().getInterfaces();
            Class<?> entityType = null;
            for (Class<?> repoInterface : interfaces) {
                entityType = extractEntityType(repoInterface);
                if (entityType != null) {
                    break;
                }
            }
            if (entityType == null) {
                return null;
            }
            if (idArg instanceof Long id) {
                return entityManager.find(entityType, id);
            }
            return entityManager.find(entityType, idArg);
        } catch (Exception e) {
            return null;
        }
    }

    private Class<?> extractEntityType(Class<?> repoInterface) {
        for (java.lang.reflect.Type type : repoInterface.getGenericInterfaces()) {
            if (type instanceof java.lang.reflect.ParameterizedType pt) {
                java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> clazz
                        && clazz.isAnnotationPresent(jakarta.persistence.Entity.class)) {
                    return clazz;
                }
            }
        }
        return null;
    }
}
