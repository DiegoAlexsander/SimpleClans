package net.sacredlabyrinth.phaed.simpleclans.redis.cache;

import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.redis.RedisManager;
import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.Jedis;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Redis cache implementation for ClanPlayer objects.
 * Uses JSON serialization for storage.
 */
public class PlayerCache implements RedisCache<UUID, ClanPlayer> {

    private static final String KEY_PREFIX = "simpleclans:player:";
    
    private final RedisManager redisManager;
    private final long defaultTtlSeconds;

    public PlayerCache(@NotNull RedisManager redisManager) {
        this.redisManager = redisManager;
        this.defaultTtlSeconds = redisManager.getConfig().getPlayerCacheTtl();
    }

    @Override
    @NotNull
    public Optional<ClanPlayer> get(@NotNull UUID uuid) {
        if (!redisManager.isInitialized()) {
            return Optional.empty();
        }

        try (Jedis jedis = redisManager.getConnection()) {
            String json = jedis.get(buildKey(uuid));
            
            if (json == null || json.isEmpty()) {
                return Optional.empty();
            }

            ClanPlayer cp = ClanPlayerSerializer.deserialize(json);
            if (cp != null) {
                redisManager.getPlugin().getLogger().fine("[Redis Cache] Player cache hit: " + uuid);
                return Optional.of(cp);
            }
        } catch (Exception e) {
            redisManager.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis Cache] Error getting player from cache: " + uuid, e);
        }
        
        return Optional.empty();
    }

    /**
     * Gets a cached player and also returns the clan tag stored with it.
     * This allows resolving the clan association without extra lookups.
     *
     * @param uuid the player UUID
     * @return CachedPlayerResult containing the player and clan tag, or empty optional
     */
    @NotNull
    public Optional<CachedPlayerResult> getWithClanTag(@NotNull UUID uuid) {
        if (!redisManager.isInitialized()) {
            return Optional.empty();
        }

        try (Jedis jedis = redisManager.getConnection()) {
            String json = jedis.get(buildKey(uuid));
            
            if (json == null || json.isEmpty()) {
                return Optional.empty();
            }

            ClanPlayer cp = ClanPlayerSerializer.deserialize(json);
            String clanTag = ClanPlayerSerializer.getClanTag(json);
            
            if (cp != null) {
                redisManager.getPlugin().getLogger().fine("[Redis Cache] Player cache hit: " + uuid);
                return Optional.of(new CachedPlayerResult(cp, clanTag));
            }
        } catch (Exception e) {
            redisManager.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis Cache] Error getting player from cache: " + uuid, e);
        }
        
        return Optional.empty();
    }

    @Override
    public void put(@NotNull UUID uuid, @NotNull ClanPlayer cp) {
        put(uuid, cp, defaultTtlSeconds);
    }

    @Override
    public void put(@NotNull UUID uuid, @NotNull ClanPlayer cp, long ttlSeconds) {
        if (!redisManager.isInitialized()) {
            return;
        }

        try (Jedis jedis = redisManager.getConnection()) {
            String json = ClanPlayerSerializer.serialize(cp);
            String key = buildKey(uuid);
            
            if (ttlSeconds > 0) {
                jedis.setex(key, ttlSeconds, json);
            } else {
                jedis.set(key, json);
            }
            
            redisManager.getPlugin().getLogger().fine("[Redis Cache] Cached player: " + uuid);
        } catch (Exception e) {
            redisManager.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis Cache] Error caching player: " + uuid, e);
        }
    }

    @Override
    public boolean remove(@NotNull UUID uuid) {
        if (!redisManager.isInitialized()) {
            return false;
        }

        try (Jedis jedis = redisManager.getConnection()) {
            long removed = jedis.del(buildKey(uuid));
            
            if (removed > 0) {
                redisManager.getPlugin().getLogger().fine("[Redis Cache] Removed player from cache: " + uuid);
                return true;
            }
        } catch (Exception e) {
            redisManager.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis Cache] Error removing player from cache: " + uuid, e);
        }
        
        return false;
    }

    @Override
    public boolean exists(@NotNull UUID uuid) {
        if (!redisManager.isInitialized()) {
            return false;
        }

        try (Jedis jedis = redisManager.getConnection()) {
            return jedis.exists(buildKey(uuid));
        } catch (Exception e) {
            redisManager.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis Cache] Error checking player existence: " + uuid, e);
        }
        
        return false;
    }

    @Override
    @NotNull
    public String getKeyPrefix() {
        return KEY_PREFIX;
    }

    /**
     * Refreshes the TTL of a cached player.
     *
     * @param uuid the player UUID
     * @return true if the key existed and TTL was refreshed
     */
    public boolean refresh(@NotNull UUID uuid) {
        if (!redisManager.isInitialized()) {
            return false;
        }

        try (Jedis jedis = redisManager.getConnection()) {
            long result = jedis.expire(buildKey(uuid), defaultTtlSeconds);
            return result == 1;
        } catch (Exception e) {
            redisManager.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis Cache] Error refreshing player TTL: " + uuid, e);
        }
        
        return false;
    }

    /**
     * Result holder for cached player with clan tag.
     */
    public static class CachedPlayerResult {
        private final ClanPlayer player;
        private final String clanTag;

        public CachedPlayerResult(@NotNull ClanPlayer player, @org.jetbrains.annotations.Nullable String clanTag) {
            this.player = player;
            this.clanTag = clanTag;
        }

        @NotNull
        public ClanPlayer getPlayer() {
            return player;
        }

        @org.jetbrains.annotations.Nullable
        public String getClanTag() {
            return clanTag;
        }

        public boolean hasClan() {
            return clanTag != null && !clanTag.isEmpty();
        }
    }
}
