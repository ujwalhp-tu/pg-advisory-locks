package org.example.lock;

import java.time.Duration;
import java.util.function.Supplier;

public interface IAdvisoryLockService {

    /**
     * Executes a task with an exclusive lock on a specific resource ID.
     *
     * @param resource The predefined lock namespace.
     * @param resourceId The specific entity ID (e.g., order ID).
     * @param timeout Maximum time to wait for the lock.
     * @param task The business logic to execute.
     * @throws LockTimeoutException if the lock cannot be acquired within the timeout.
     */
    void executeWithLock(LockResource resource, String resourceId, Duration timeout, Runnable task);

    /**
     * Executes a task with an exclusive lock on a specific resource ID and returns a result.
     *
     * @param resource The predefined lock namespace.
     * @param resourceId The specific entity ID (e.g., order ID).
     * @param timeout Maximum time to wait for the lock.
     * @param task The business logic to execute.
     * @return The result of the task.
     * @throws LockTimeoutException if the lock cannot be acquired within the timeout.
     */
    <T> T executeWithLock(LockResource resource, String resourceId, Duration timeout, Supplier<T> task);

    /**
     * Executes a task with a global lock (no specific resource ID).
     * Useful for cron jobs or singletons.
     *
     * @param resource The predefined lock namespace.
     * @param timeout Maximum time to wait for the lock.
     * @param task The business logic to execute.
     * @throws LockTimeoutException if the lock cannot be acquired within the timeout.
     */
    void executeWithLock(LockResource resource, Duration timeout, Runnable task);

    /**
     * Executes a task with a global lock (no specific resource ID) and returns a result.
     *
     * @param resource The predefined lock namespace.
     * @param timeout Maximum time to wait for the lock.
     * @param task The business logic to execute.
     * @return The result of the task.
     * @throws LockTimeoutException if the lock cannot be acquired within the timeout.
     */
    <T> T executeWithLock(LockResource resource, Duration timeout, Supplier<T> task);

    /**
     * Executes a task with an exclusive lock, blocking indefinitely until acquired.
     *
     * @param resource The predefined lock namespace.
     * @param resourceId The specific entity ID.
     * @param task The business logic to execute.
     */
    void executeWithLock(LockResource resource, String resourceId, Runnable task);

    /**
     * Executes a task with an exclusive lock, blocking indefinitely until acquired.
     *
     * @param resource The predefined lock namespace.
     * @param resourceId The specific entity ID.
     * @param task The business logic to execute.
     * @return The result of the task.
     */
    <T> T executeWithLock(LockResource resource, String resourceId, Supplier<T> task);

    /**
     * Executes a task with a global lock, blocking indefinitely until acquired.
     *
     * @param resource The predefined lock namespace.
     * @param task The business logic to execute.
     */
    void executeWithLock(LockResource resource, Runnable task);

    /**
     * Executes a task with a global lock, blocking indefinitely until acquired.
     *
     * @param resource The predefined lock namespace.
     * @param task The business logic to execute.
     * @return The result of the task.
     */
    <T> T executeWithLock(LockResource resource, Supplier<T> task);
}
