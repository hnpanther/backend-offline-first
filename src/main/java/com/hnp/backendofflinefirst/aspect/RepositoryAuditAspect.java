package com.hnp.backendofflinefirst.aspect;

import com.hnp.backendofflinefirst.audit.AuditEntitySupport;
import com.hnp.backendofflinefirst.domain.AuditAction;
import com.hnp.backendofflinefirst.service.AuditService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;

/**
 * Captures CREATE/UPDATE/DELETE on JPA entities at the repository layer.
 * Loads the previous DB snapshot before save/delete; persistence is async via {@link AuditService}.
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
        Object entity = pjp.getArgs()[0];
        if (!AuditEntitySupport.shouldAudit(entity)) {
            return pjp.proceed();
        }

        Object oldSnapshot = loadSnapshot(entity);
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

    private Object loadSnapshot(Object entity) {
        Class<?> type = Hibernate.getClass(entity);
        if (type.getAnnotation(IdClass.class) != null) {
            Object compositeId = buildCompositeId(entity, type);
            if (compositeId == null) {
                return null;
            }
            return entityManager.find(type, compositeId);
        }
        String entityId = AuditEntitySupport.entityId(entity);
        if (entityId == null) {
            return null;
        }
        try {
            Long id = Long.parseLong(entityId);
            return entityManager.find(type, id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object buildCompositeId(Object entity, Class<?> entityType) {
        IdClass idClassAnn = entityType.getAnnotation(IdClass.class);
        if (idClassAnn == null) {
            return null;
        }
        try {
            Class<?> idClass = idClassAnn.value();
            Object id = idClass.getDeclaredConstructor().newInstance();
            for (Field entityField : entityType.getDeclaredFields()) {
                if (!entityField.isAnnotationPresent(Id.class)) {
                    continue;
                }
                entityField.setAccessible(true);
                Field idField = idClass.getDeclaredField(entityField.getName());
                idField.setAccessible(true);
                idField.set(id, entityField.get(entity));
            }
            return id;
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
