package net.sacredlabyrinth.phaed.simpleclans.redis.cache;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.redis.RedisManager;
import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.Jedis;

import java.util.Optional;
import java.util.logging.Level;

/**
 * Redis cache implementation for Clan objects.
 * Uses JSON serialization for storage.
 */
public class ClanCache implements RedisCache<String, Clan> {

    private static final String KEY_PREFIX = "simpleclans:clan:";
    
    private final RedisManager redisManager;
    private final long defaultTtlSeconds;

    public ClanCache(@NotNull RedisManager redisManager) {
        this.redisManager = redisManager;
        this.defaultTtlSeconds = redisManager.getConfig().getClanCacheTtl();
    }

    @Override
    @NotNull
    public Optional<Clan> get(@NotNull String tag) {
        if (!redisManager.isInitialized()) {
            return Optional.empty();
        }

        try (Jedis jedis = redisManager.getConnection()) {
            String json = jedis.get(buildKey(tag));
            
            if (json == null || json.isEmpty()) {
                return Optional.empty();
            }

            Clan clan = ClanSerializer.deserialize(json);
            if (clan != null) {
                redisManager.getPlugin().getLogger().fine("[Redis Cache] Clan cache hit: " + tag);
                return Optional.of(clan);
            }
        } catch (Exception e) {
            redisManager.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis Cache] Error getting clan from cache: " + tag, e);
        }
        
        return Optional.empty();
    }

    @Override
    public void put(@NotNull String tag, @NotNull Clan clan) {
        put(tag, clan, defaultTtlSeconds);
    }

    @Override
    public void put(@NotNull String tag, @NotNull Clan clan, long ttlSeconds) {
        if (!redisManager.isInitialized()) {
            return;
        }

        try (Jedis jedis = redisManager.getConnection()) {
            String json = ClanSerializer.serialize(clan);
            String key = buildKey(tag);
            
            if (ttlSeconds > 0) {
                jedis.setex(key, ttlSeconds, json);
            } else {
                jedis.set(key, json);
            }
            
            redisManager.getPlugin().getLogger().fine("[Redis Cache] Cached clan: " + tag);
        } catch (Exception e) {
            redisManager.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis Cache] Error caching clan: " + tag, e);
        }
    }

    @Override
    public boolean remove(@NotNull String tag) {
        if (!redisManager.isInitialized()) {
            return false;
        }

        try (Jedis jedis = redisManager.getConnection()) {
            long removed = jedis.del(buildKey(tag));
            
            if (removed > 0) {
                redisManager.getPlugin().getLogger().fine("[Redis Cache] Removed clan from cache: " + tag);
                return true;
            }
        } catch (Exception e) {
            redisManager.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis Cache] Error removing clan from cache: " + tag, e);
        }
        
        return false;
    }

    @Override
    public boolean exists(@NotNull String tag) {
        if (!redisManager.isInitialized()) {
            return false;
        }

        try (Jedis jedis = redisManager.getConnection()) {
            return jedis.exists(buildKey(tag));
        } catch (Exception e) {
            redisManager.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis Cache] Error checking clan existence: " + tag, e);
        }
        
        return false;
    }

    @Override
    @NotNull
    public String getKeyPrefix() {
        return KEY_PREFIX;
    }

    /**
     * Refreshes the TTL of a cached clan.
     *
     * @param tag the clan tag
     * @return true if the key existed and TTL was refreshed
     */
    public boolean refresh(@NotNull String tag) {
        if (!redisManager.isInitialized()) {
            return false;
        }

        try (Jedis jedis = redisManager.getConnection()) {
            long result = jedis.expire(buildKey(tag), defaultTtlSeconds);
            return result == 1;
        } catch (Exception e) {
            redisManager.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis Cache] Error refreshing clan TTL: " + tag, e);
        }
        
        return false;
    }
}
