package net.sacredlabyrinth.phaed.simpleclans.redis.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Generic interface for Redis cache operations.
 *
 * @param <K> Key type
 * @param <V> Value type
 */
public interface RedisCache<K, V> {

    /**
     * Gets a value from the cache.
     *
     * @param key the key
     * @return Optional containing the value, or empty if not found
     */
    @NotNull
    Optional<V> get(@NotNull K key);

    /**
     * Puts a value in the cache with default TTL.
     *
     * @param key   the key
     * @param value the value
     */
    void put(@NotNull K key, @NotNull V value);

    /**
     * Puts a value in the cache with custom TTL.
     *
     * @param key       the key
     * @param value     the value
     * @param ttlSeconds TTL in seconds
     */
    void put(@NotNull K key, @NotNull V value, long ttlSeconds);

    /**
     * Removes a value from the cache.
     *
     * @param key the key
     * @return true if the key was present
     */
    boolean remove(@NotNull K key);

    /**
     * Checks if a key exists in the cache.
     *
     * @param key the key
     * @return true if the key exists
     */
    boolean exists(@NotNull K key);

    /**
     * Gets the cache key prefix.
     *
     * @return the key prefix
     */
    @NotNull
    String getKeyPrefix();

    /**
     * Builds the full Redis key from a logical key.
     *
     * @param key the logical key
     * @return the full Redis key
     */
    @NotNull
    default String buildKey(@NotNull K key) {
        return getKeyPrefix() + key.toString();
    }
}
