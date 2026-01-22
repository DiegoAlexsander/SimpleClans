package net.sacredlabyrinth.phaed.simpleclans.redis.lock;

import java.io.Closeable;

/**
 * Interface for distributed locks.
 * Implementations should be used with try-with-resources.
 */
public interface DistributedLock extends Closeable {

    /**
     * Attempts to acquire the lock.
     * Blocks until the lock is acquired or timeout is reached.
     * 
     * @param timeoutMs maximum time to wait in milliseconds
     * @return true if the lock was acquired, false if timed out
     */
    boolean tryAcquire(long timeoutMs);

    /**
     * Attempts to acquire the lock with the default timeout.
     * 
     * @return true if the lock was acquired, false if timed out
     */
    boolean tryAcquire();

    /**
     * Releases the lock if held by this instance.
     * Safe to call multiple times.
     */
    void release();

    /**
     * Checks if this instance currently holds the lock.
     * 
     * @return true if locked
     */
    boolean isLocked();

    /**
     * Returns the resource being locked.
     * 
     * @return the resource name
     */
    String getResource();

    /**
     * Closes the lock, releasing it if held.
     * This is equivalent to calling release().
     */
    @Override
    default void close() {
        release();
    }
}
