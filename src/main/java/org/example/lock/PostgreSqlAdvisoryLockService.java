package org.example.lock;

import com.google.common.hash.Hashing;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * PostgreSQL advisory lock service based on transaction-level locks.
 * Uses a callback pattern to ensure locks are always released (via transaction commit/rollback).
 */
@Service
public class PostgreSqlAdvisoryLockService implements IAdvisoryLockService {

    private static final Logger log = LoggerFactory.getLogger(PostgreSqlAdvisoryLockService.class);
    private static final String GLOBAL_RESOURCE_ID = "GLOBAL";
    private static final long RETRY_DELAY_MS = 100;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void executeWithLock(LockResource resource, String resourceId, Duration timeout, Runnable task) {
        executeWithLock(resource, resourceId, timeout, () -> {
            task.run();
            return null;
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> T executeWithLock(LockResource resource, String resourceId, Duration timeout, Supplier<T> task) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeout.toMillis();

        while (true) {
            if (tryAcquireLock(resource.getNamespace(), resourceId)) {
                log.debug("Acquired lock for {}:{}", resource.getNamespace(), resourceId);
                return task.get();
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new LockTimeoutException(
                        String.format("Failed to acquire lock for %s:%s within %d ms", 
                                resource.getNamespace(), resourceId, timeoutMs)
                );
            }

            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for lock", e);
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeWithLock(LockResource resource, Duration timeout, Runnable task) {
        executeWithLock(resource, GLOBAL_RESOURCE_ID, timeout, task);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> T executeWithLock(LockResource resource, Duration timeout, Supplier<T> task) {
        return executeWithLock(resource, GLOBAL_RESOURCE_ID, timeout, task);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void executeWithLock(LockResource resource, String resourceId, Runnable task) {
        executeWithLock(resource, resourceId, () -> {
            task.run();
            return null;
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> T executeWithLock(LockResource resource, String resourceId, Supplier<T> task) {
        acquireLockIndefinitely(resource.getNamespace(), resourceId);
        log.debug("Acquired indefinite lock for {}:{}", resource.getNamespace(), resourceId);
        return task.get();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void executeWithLock(LockResource resource, Runnable task) {
        executeWithLock(resource, GLOBAL_RESOURCE_ID, task);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> T executeWithLock(LockResource resource, Supplier<T> task) {
        return executeWithLock(resource, GLOBAL_RESOURCE_ID, task);
    }

    /**
     * Attempts to acquire a transaction-level advisory lock using a 64-bit Murmur3 hash.
     * This provides superior collision resistance compared to Postgres hashtext().
     */
    private boolean tryAcquireLock(String namespace, String resourceId) {
        long lockKey = generateHashKey(namespace, resourceId);

        Query query = entityManager.createNativeQuery(
                "SELECT pg_try_advisory_xact_lock(?1)"
        );
        query.setParameter(1, lockKey);
        
        Boolean result = (Boolean) query.getSingleResult();
        return Boolean.TRUE.equals(result);
    }

    /**
     * Acquires a transaction-level advisory lock, blocking indefinitely until available.
     */
    private void acquireLockIndefinitely(String namespace, String resourceId) {
        long lockKey = generateHashKey(namespace, resourceId);

        Query query = entityManager.createNativeQuery(
                "SELECT pg_advisory_xact_lock(?1)"
        );
        query.setParameter(1, lockKey);
        query.getSingleResult();
    }

    private long generateHashKey(String namespace, String resourceId) {
        String rawKey = namespace + ":" + resourceId;
        return Hashing.murmur3_128()
                .hashString(rawKey, StandardCharsets.UTF_8)
                .asLong();
    }
}
