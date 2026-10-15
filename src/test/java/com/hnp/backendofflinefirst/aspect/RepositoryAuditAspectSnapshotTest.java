package com.hnp.backendofflinefirst.aspect;

import com.hnp.backendofflinefirst.domain.AuditAction;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.service.AuditService;
import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The pre-change read behind an audited UPDATE, and the three things that must be true of it.
 *
 * <p>It runs on a <b>second</b> connection while the caller's transaction still holds its first,
 * because nothing cheaper can answer the question (see the aspect's own javadoc). That makes it
 * the one part of a write that can be starved by the connection pool — and before these tests,
 * being starved came out of the aspect as <em>the caller's</em> exception. A supervisor pressing
 * save during a busy minute got an error page, for the sake of a row nobody had asked for.
 */
@ExtendWith(MockitoExtension.class)
class RepositoryAuditAspectSnapshotTest {

    @Mock AuditService auditService;
    @Mock EntityManager entityManager;
    @Mock ProceedingJoinPoint pjp;

    RepositoryAuditAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new RepositoryAuditAspect(auditService);
        ReflectionTestUtils.setField(aspect, "entityManager", entityManager);
    }

    @Test
    void aPreChangeReadThatFailsDoesNotFailTheWrite() throws Throwable {
        AssetEntry existing = withId(42L);
        when(pjp.getArgs()).thenReturn(new Object[]{existing});
        when(pjp.proceed()).thenReturn(existing);
        when(entityManager.getEntityManagerFactory())
                .thenThrow(new IllegalStateException("connection pool exhausted"));

        Object result = aspect.auditSave(pjp);

        assertThat(result).isSameAs(existing);
        verify(pjp).proceed();
        // No row rather than a wrong one: an UPDATE whose previous values could not be read is
        // not a CREATE, and writing it as one would put a confident falsehood in the audit trail.
        verifyNoInteractions(auditService);
    }

    @Test
    void aPreChangeReadWithNoPermitIsSkippedWithoutTouchingThePool() throws Throwable {
        AssetEntry existing = withId(42L);
        when(pjp.getArgs()).thenReturn(new Object[]{existing});
        when(pjp.proceed()).thenReturn(existing);
        ReflectionTestUtils.setField(aspect, "snapshotWaitMs", 0L);
        ReflectionTestUtils.setField(aspect, "snapshotPermits", new Semaphore(0));

        Object result = aspect.auditSave(pjp);

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(entityManager);
        verifyNoInteractions(auditService);
    }

    @Test
    void aFreshRowNeverOpensASecondEntityManagerAtAll() throws Throwable {
        AssetEntry fresh = new AssetEntry();                     // id still null
        when(pjp.getArgs()).thenReturn(new Object[]{fresh});
        when(pjp.proceed()).thenReturn(fresh);

        aspect.auditSave(pjp);

        // The cheap half of the cost claim: an insert of a generated-id entity pays nothing here.
        // An insert of an assigned or composite id entity does pay, and always finds nothing —
        // that one is irreducible and is recorded in the aspect's javadoc instead.
        verifyNoInteractions(entityManager);
        verify(auditService).recordChange(isNull(), same(fresh), eq(AuditAction.CREATE));
    }

    private static AssetEntry withId(long id) {
        AssetEntry entry = new AssetEntry();
        entry.setId(id);
        return entry;
    }
}
