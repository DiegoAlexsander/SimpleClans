package net.sacredlabyrinth.phaed.simpleclans.redis.lock;

import net.sacredlabyrinth.phaed.simpleclans.redis.RedisManager;
import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Distributed lock implementation using Redis.
 * Uses the Redlock algorithm (simplified single-node version).
 * 
 * <p>Lock key format: simpleclans:lock:{resource}</p>
 * <p>Lock value: unique identifier (UUID) to ensure only the owner can release</p>
 */
public class RedisLock implements DistributedLock {

    private static final String PREFIX = "simpleclans:lock:";
    private static final int RETRY_DELAY_MS = 50;
    
    // Lua script for atomic unlock (only if we still own the lock)
    private static final String UNLOCK_SCRIPT = 
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "   return redis.call('del', KEYS[1]) " +
            "else " +
            "   return 0 " +
            "end";
    
    private final RedisManager redis;
    private final String resource;
    private final String lockKey;
    private final String lockId;
    private final long defaultTimeoutMs;
    
    private volatile boolean locked = false;

    /**
     * Creates a new distributed lock.
     * 
     * @param redis the Redis manager
     * @param resource the resource to lock (e.g., "bank:ABC")
     * @param defaultTimeoutMs default timeout for tryAcquire()
     */
    public RedisLock(@NotNull RedisManager redis, @NotNull String resource, long defaultTimeoutMs) {
        this.redis = redis;
        this.resource = resource;
        this.lockKey = PREFIX + resource;
        this.lockId = UUID.randomUUID().toString();
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    @Override
    public boolean tryAcquire(long timeoutMs) {
        if (locked) {
            return true; // Already locked by us
        }
        
        long deadline = System.currentTimeMillis() + timeoutMs;
        
        while (System.currentTimeMillis() < deadline) {
            try (Jedis jedis = redis.getConnection()) {
                // SET NX PX - Set if Not eXists with eXpiration
                // The lock expires automatically to prevent deadlocks if the holder crashes
                String result = jedis.set(lockKey, lockId,
                        SetParams.setParams().nx().px(defaultTimeoutMs));
                
                if ("OK".equals(result)) {
                    locked = true;
                    return true;
                }
            } catch (Exception e) {
                redis.getPlugin().getLogger().log(Level.WARNING,
                        "[Redis] Error acquiring lock for " + resource, e);
                return false;
            }
            
            // Wait before retrying
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        // Timeout reached
        return false;
    }

    @Override
    public boolean tryAcquire() {
        return tryAcquire(defaultTimeoutMs);
    }

    @Override
    public void release() {
        if (!locked) {
            return;
        }
        
        try (Jedis jedis = redis.getConnection()) {
            // Use Lua script for atomic check-and-delete
            // This ensures we only delete the lock if we still own it
            jedis.eval(UNLOCK_SCRIPT, 
                    Collections.singletonList(lockKey),
                    Collections.singletonList(lockId));
            
            locked = false;
        } catch (Exception e) {
            redis.getPlugin().getLogger().log(Level.WARNING,
                    "[Redis] Error releasing lock for " + resource, e);
        }
    }

    @Override
    public boolean isLocked() {
        return locked;
    }

    @Override
    public String getResource() {
        return resource;
    }

    /**
     * Extends the lock's TTL.
     * Useful for long-running operations.
     * 
     * @param additionalMs additional milliseconds to add to the TTL
     * @return true if the lock was extended, false if not owned or error
     */
    public boolean extend(long additionalMs) {
        if (!locked) {
            return false;
        }
        
        String extendScript = 
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "   return redis.call('pexpire', KEYS[1], ARGV[2]) " +
                "else " +
                "   return 0 " +
                "end";
        
        try (Jedis jedis = redis.getConnection()) {
            Object result = jedis.eval(extendScript,
                    Collections.singletonList(lockKey),
                    java.util.Arrays.asList(lockId, String.valueOf(additionalMs)));
            
            return result != null && ((Long) result) == 1L;
        } catch (Exception e) {
            redis.getPlugin().getLogger().log(Level.WARNING,
                    "[Redis] Error extending lock for " + resource, e);
            return false;
        }
    }

    @Override
    public String toString() {
        return "RedisLock{" +
                "resource='" + resource + '\'' +
                ", lockId='" + lockId + '\'' +
                ", locked=" + locked +
                '}';
    }
}
