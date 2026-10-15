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
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Hibernate;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
@Slf4j
public class RepositoryAuditAspect {

    private final AuditService auditService;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * How many pre-change reads may be in flight at once.
     *
     * <p>Each one borrows a <b>second</b> connection while the caller's transaction is still
     * holding its first — see {@link #snapshotFromSeparateEntityManager}. Unbounded, that is a
     * pool deadlock waiting for enough concurrent audited writes: with Boot's default pool of ten,
     * ten requests each holding one connection and each asking for another can only wait for each
     * other until the connection timeout fires — which, before this cap and the guard in
     * {@link #capturePriorState}, failed <em>the user's write</em>.
     *
     * <p>Bounded, the same pile-up resolves: whoever cannot get a permit within
     * {@code app.audit.snapshot.wait-ms} gives up, releases nothing it was not already holding,
     * and the write proceeds with its audit detail skipped rather than failing. Four is
     * deliberately well under any sane pool size, and in ordinary operation — a handful of
     * administrators saving forms — it is never contended at all.
     *
     * <p>Field-level default, not {@code final}: a plain unit test builds this aspect without
     * Spring, exactly as {@code LogSheetService} does with its batch ceiling.
     */
    @Value("${app.audit.snapshot.max-concurrent:4}")
    private int maxConcurrentSnapshots = 4;

    /** How long a pre-change read waits for a permit before giving the audit row up. */
    @Value("${app.audit.snapshot.wait-ms:5000}")
    private long snapshotWaitMs = 5_000L;

    private volatile Semaphore snapshotPermits;

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

        PriorState prior = capturePriorState(entity);
        if (prior.unreadable()) {
            // The audit trail is never the reason a legitimate write fails. Before this branch
            // existed, a connection the pool could not supply inside the pre-change read came out
            // of this aspect as the caller's exception — so a busy moment turned a supervisor's
            // save into an error page, over a row nobody had asked for.
            return pjp.proceed();
        }

        Object result = pjp.proceed();
        Object saved = result != null ? result : entity;
        auditService.recordChange(prior.values(), saved, prior.action());
        return result;
    }

    /**
     * What the committed row held before this save — or the fact that it could not be read.
     *
     * <p>The distinction is the whole point of this type. {@code null} values with
     * {@code unreadable() == false} mean <b>there is no committed row</b>, which is a CREATE and
     * is ordinary. {@code unreadable()} means <b>we do not know</b>, and the two must never be
     * confused: recording an UPDATE as a CREATE because the read failed would put a confident
     * falsehood in the one table whose job is to be evidence.
     */
    private record PriorState(Map<String, Object> values, boolean unreadable) {

        static PriorState create() {
            return new PriorState(null, false);
        }

        static PriorState of(Map<String, Object> values) {
            return new PriorState(values, false);
        }

        /** Named away from the {@code unreadable()} accessor, which a record reserves. */
        static PriorState unknown() {
            return new PriorState(null, true);
        }

        AuditAction action() {
            return values == null ? AuditAction.CREATE : AuditAction.UPDATE;
        }
    }

    /** Never throws: anything that goes wrong reading the old row is reported, not propagated. */
    private PriorState capturePriorState(Object entity) {
        try {
            Map<String, Object> committed = captureIndependentOldValues(entity);
            return committed == null ? PriorState.create() : PriorState.of(committed);
        } catch (RuntimeException e) {
            // Type and id only. The values are what we failed to read, and some of them would be
            // secrets if we had.
            log.warn("Could not read the pre-change state of {} {} ({}); the write proceeds and "
                            + "this change is not in the audit trail.",
                    AuditEntitySupport.entityType(entity), AuditEntitySupport.entityId(entity),
                    e.getMessage());
            return PriorState.unknown();
        }
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

    /**
     * Reads the committed row on a connection of its own, and is the reason this aspect costs
     * what it costs.
     *
     * <p><b>The second connection is load-bearing, not an oversight.</b> The current persistence
     * context cannot answer this question: by the time a service calls {@code save}, a validation
     * query has usually triggered an auto-flush, so both the managed instance and Hibernate's
     * {@code loadedState} already hold the <em>new</em> values, and the database row this
     * transaction can see holds them too. Only a second transaction, at READ COMMITTED, still
     * sees what was committed before this one started. There is no cheaper place to get it.
     *
     * <p>What that means for cost, stated plainly because no document used to say it:
     * <ul>
     *   <li>An insert of a generated-id entity costs <b>nothing</b> here — the id is still null,
     *       so {@link #captureIndependentOldValues} returns before this method is reached.</li>
     *   <li>An update costs one extra connection acquisition and one extra single-row SELECT.</li>
     *   <li>An insert of an <b>assigned or composite id</b> entity — {@code UnitSupervisor},
     *       {@code UnitOperator}, {@code UserRole}, {@code RolePermission}, {@code LocationUnit},
     *       {@code LogSheetTemplateAsset} — also pays that read, <b>and it always finds
     *       nothing</b>, because the id is populated before the row exists. Granting a role 50
     *       permissions, or importing 10,000 staff links, pays it per row.</li>
     * </ul>
     *
     * <p>The entity manager is created and closed per call rather than kept for the transaction
     * on purpose: a short borrow returned immediately is far kinder to the pool than one
     * connection held for the length of a long import.
     */
    private Map<String, Object> snapshotFromSeparateEntityManager(Class<?> type, Object persistenceId) {
        if (!acquireSnapshotPermit()) {
            throw new SnapshotUnavailableException(
                    "no audit snapshot permit within " + snapshotWaitMs + " ms");
        }
        EntityManager freshEm = null;
        try {
            EntityManagerFactory emf = entityManager.getEntityManagerFactory();
            freshEm = emf.createEntityManager();
            Object fresh = freshEm.find(type, persistenceId);
            if (fresh == null) {
                return null;
            }
            return AuditEntitySupport.captureFieldValues(fresh);
        } finally {
            if (freshEm != null && freshEm.isOpen()) {
                freshEm.close();
            }
            permits().release();
        }
    }

    private boolean acquireSnapshotPermit() {
        try {
            return permits().tryAcquire(snapshotWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SnapshotUnavailableException("interrupted while waiting for a snapshot permit");
        }
    }

    /**
     * Lazily built so the aspect works when it is constructed with {@code new} in a unit test,
     * where {@code @Value} never runs and {@code @PostConstruct} is never called.
     */
    private Semaphore permits() {
        Semaphore existing = snapshotPermits;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (snapshotPermits == null) {
                snapshotPermits = new Semaphore(Math.max(1, maxConcurrentSnapshots));
            }
            return snapshotPermits;
        }
    }

    /** The pre-change read could not be performed — distinct from "there is no previous row". */
    static final class SnapshotUnavailableException extends RuntimeException {
        SnapshotUnavailableException(String message) {
            super(message);
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
