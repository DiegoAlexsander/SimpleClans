package net.sacredlabyrinth.phaed.simpleclans.redis.request;

import net.sacredlabyrinth.phaed.simpleclans.Request;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.managers.ClanManager;
import net.sacredlabyrinth.phaed.simpleclans.redis.RedisManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.Jedis;

import java.util.*;
import java.util.logging.Level;

/**
 * Redis storage for clan requests (invites, ally proposals, etc.).
 * <p>
 * This class provides persistent storage for requests across multiple servers.
 * Requests are stored with a TTL to automatically expire after a configurable time.
 * </p>
 * 
 * <p>
 * Key structure:
 * <ul>
 *   <li>{@code simpleclans:requests:{key}} - Individual request data</li>
 *   <li>{@code simpleclans:requests:index} - Set of all active request keys</li>
 * </ul>
 * 
 * @since 2.0
 */
public class RedisRequestStorage {

    private static final String KEY_PREFIX = "simpleclans:requests:";
    private static final String INDEX_KEY = "simpleclans:requests:index";
    
    /** Default TTL for requests: 5 minutes */
    private static final int DEFAULT_TTL_SECONDS = 300;

    private final RedisManager redisManager;
    private final SimpleClans plugin;
    private final int ttlSeconds;

    /**
     * Creates a new RedisRequestStorage.
     *
     * @param redisManager the Redis manager
     * @param plugin       the plugin instance
     */
    public RedisRequestStorage(@NotNull RedisManager redisManager, @NotNull SimpleClans plugin) {
        this.redisManager = redisManager;
        this.plugin = plugin;
        // Could be made configurable in the future
        this.ttlSeconds = DEFAULT_TTL_SECONDS;
    }

    /**
     * Stores a request in Redis.
     *
     * @param key     the request key (usually target name or clan tag)
     * @param request the request to store
     * @return true if stored successfully
     */
    public boolean storeRequest(@NotNull String key, @NotNull Request request) {
        String normalizedKey = key.toLowerCase();
        String redisKey = KEY_PREFIX + normalizedKey;
        String json = RequestSerializer.serialize(request);

        try (Jedis jedis = redisManager.getResource()) {
            if (jedis == null) {
                plugin.getLogger().warning("[Redis Request] Cannot store request - no Redis connection: " + key);
                return false;
            }
            
            // Store the request with TTL
            jedis.setex(redisKey, ttlSeconds, json);
            
            // Add to index for listing
            jedis.sadd(INDEX_KEY, normalizedKey);
            
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to store request in Redis: " + key, e);
            return false;
        }
    }

    /**
     * Retrieves a request from Redis.
     *
     * @param key the request key
     * @return the request, or null if not found
     */
    @Nullable
    public Request getRequest(@NotNull String key) {
        String normalizedKey = key.toLowerCase();
        String redisKey = KEY_PREFIX + normalizedKey;

        try (Jedis jedis = redisManager.getResource()) {
            if (jedis == null) {
                plugin.getLogger().warning("[Redis Request] Cannot get request - no Redis connection: " + key);
                return null;
            }
            
            String json = jedis.get(redisKey);
            if (json == null) {
                // Clean up index if key doesn't exist
                jedis.srem(INDEX_KEY, normalizedKey);
                plugin.getLogger().fine(() -> "[Redis Request] Request not found in Redis: " + key);
                return null;
            }

            RequestSerializer.SerializableRequest sr = RequestSerializer.deserialize(json);
            if (sr == null) {
                plugin.getLogger().warning("[Redis Request] Failed to deserialize request: " + key);
                return null;
            }

            ClanManager clanManager = plugin.getClanManager();
            Request request = RequestSerializer.toRequest(sr, clanManager);
            return request;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get request from Redis: " + key, e);
            return null;
        }
    }

    /**
     * Checks if a request exists in Redis.
     *
     * @param key the request key
     * @return true if the request exists
     */
    public boolean hasRequest(@NotNull String key) {
        String normalizedKey = key.toLowerCase();
        String redisKey = KEY_PREFIX + normalizedKey;

        try (Jedis jedis = redisManager.getResource()) {
            if (jedis == null) {
                return false;
            }
            return jedis.exists(redisKey);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check request in Redis: " + key, e);
            return false;
        }
    }

    /**
     * Removes a request from Redis.
     *
     * @param key the request key
     * @return true if removed successfully
     */
    public boolean removeRequest(@NotNull String key) {
        String normalizedKey = key.toLowerCase();
        String redisKey = KEY_PREFIX + normalizedKey;

        try (Jedis jedis = redisManager.getResource()) {
            if (jedis == null) {
                return false;
            }
            
            jedis.del(redisKey);
            jedis.srem(INDEX_KEY, normalizedKey);
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to remove request from Redis: " + key, e);
            return false;
        }
    }

    /**
     * Gets all active request keys from Redis.
     *
     * @return set of request keys
     */
    @NotNull
    public Set<String> getAllRequestKeys() {
        try (Jedis jedis = redisManager.getResource()) {
            if (jedis == null) {
                return Collections.emptySet();
            }
            
            Set<String> keys = jedis.smembers(INDEX_KEY);
            if (keys == null) {
                return Collections.emptySet();
            }
            
            // Clean up expired entries from index
            Set<String> validKeys = new HashSet<>();
            for (String key : keys) {
                if (jedis.exists(KEY_PREFIX + key)) {
                    validKeys.add(key);
                } else {
                    jedis.srem(INDEX_KEY, key);
                }
            }
            
            return validKeys;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get request keys from Redis", e);
            return Collections.emptySet();
        }
    }

    /**
     * Gets all active requests from Redis.
     *
     * @return map of key to request
     */
    @NotNull
    public Map<String, Request> getAllRequests() {
        Map<String, Request> result = new HashMap<>();
        
        try (Jedis jedis = redisManager.getResource()) {
            if (jedis == null) {
                return result;
            }
            
            Set<String> keys = getAllRequestKeys();
            ClanManager clanManager = plugin.getClanManager();
            
            for (String key : keys) {
                String json = jedis.get(KEY_PREFIX + key);
                if (json != null) {
                    RequestSerializer.SerializableRequest sr = RequestSerializer.deserialize(json);
                    if (sr != null) {
                        Request request = RequestSerializer.toRequest(sr, clanManager);
                        if (request != null) {
                            result.put(key, request);
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get all requests from Redis", e);
        }
        
        return result;
    }

    /**
     * Updates a request in Redis (e.g., when a vote is cast).
     *
     * @param key     the request key
     * @param request the updated request
     * @return true if updated successfully
     */
    public boolean updateRequest(@NotNull String key, @NotNull Request request) {
        // Just re-store with the same key - TTL will be refreshed
        return storeRequest(key, request);
    }

    /**
     * Clears all requests from Redis.
     * Use with caution - this affects all servers.
     */
    public void clearAllRequests() {
        try (Jedis jedis = redisManager.getResource()) {
            if (jedis == null) {
                return;
            }
            
            Set<String> keys = jedis.smembers(INDEX_KEY);
            if (keys != null) {
                for (String key : keys) {
                    jedis.del(KEY_PREFIX + key);
                }
            }
            jedis.del(INDEX_KEY);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to clear requests from Redis", e);
        }
    }

    /**
     * Gets the TTL (time-to-live) in seconds for requests.
     *
     * @return TTL in seconds
     */
    public int getTtlSeconds() {
        return ttlSeconds;
    }
}
