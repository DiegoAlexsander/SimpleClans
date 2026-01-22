package net.sacredlabyrinth.phaed.simpleclans.redis;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Configuration holder for Redis connection settings.
 * Loads values from config.yml and provides defaults.
 */
public class RedisConfiguration {

    private final SimpleClans plugin;
    
    // Connection
    private boolean enabled;
    private String host;
    private int port;
    private String username;
    private String password;
    private boolean ssl;
    
    // Pool
    private int poolMaxTotal;
    private int poolMaxIdle;
    private int poolMinIdle;
    private int poolTimeout;
    
    // Server identification
    private String serverId;
    
    // Cache TTL
    private int clanCacheTtl;
    private int playerCacheTtl;
    
    // Locks
    private int lockBankTimeout;
    private int lockDisbandTimeout;
    private int lockDefaultTimeout;
    
    // Data TTL
    private int requestTtl;
    private int voteTtl;
    
    // Reconnection
    private int reconnectDelay;
    private int reconnectMaxAttempts;

    public RedisConfiguration(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads configuration from config.yml.
     * Should be called on plugin enable and reload.
     */
    public void load() {
        FileConfiguration config = plugin.getConfig();
        
        // Connection
        enabled = config.getBoolean("redis.enable", false);
        host = config.getString("redis.host", "localhost");
        port = config.getInt("redis.port", 6379);
        username = config.getString("redis.username", "");
        password = config.getString("redis.password", "");
        ssl = config.getBoolean("redis.ssl", false);
        
        // Pool
        poolMaxTotal = config.getInt("redis.pool.max-total", 16);
        poolMaxIdle = config.getInt("redis.pool.max-idle", 8);
        poolMinIdle = config.getInt("redis.pool.min-idle", 2);
        poolTimeout = config.getInt("redis.pool.timeout", 3000);
        
        // Server ID
        String configuredServerId = config.getString("redis.server-id", "");
        if (configuredServerId == null || configuredServerId.isEmpty()) {
            serverId = "server-" + UUID.randomUUID().toString().substring(0, 8);
            plugin.getLogger().info("[Redis] Auto-generated server-id: " + serverId);
        } else {
            serverId = configuredServerId;
        }
        
        // Cache TTL (in seconds)
        clanCacheTtl = config.getInt("redis.cache.clan-ttl", 300);
        playerCacheTtl = config.getInt("redis.cache.player-ttl", 300);
        
        // Locks (in milliseconds)
        lockBankTimeout = config.getInt("redis.locks.bank-timeout", 5000);
        lockDisbandTimeout = config.getInt("redis.locks.disband-timeout", 30000);
        lockDefaultTimeout = config.getInt("redis.locks.default-timeout", 10000);
        
        // Data TTL (in seconds)
        requestTtl = config.getInt("redis.requests.ttl", 300);
        voteTtl = config.getInt("redis.votes.ttl", 120);
        
        // Reconnection
        reconnectDelay = config.getInt("redis.reconnect.delay", 5000);
        reconnectMaxAttempts = config.getInt("redis.reconnect.max-attempts", 10);
    }

    // ==================== Getters ====================

    public boolean isEnabled() {
        return enabled;
    }

    @NotNull
    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Nullable
    public String getUsername() {
        return (username != null && !username.isEmpty()) ? username : null;
    }

    @Nullable
    public String getPassword() {
        return (password != null && !password.isEmpty()) ? password : null;
    }

    public boolean isSsl() {
        return ssl;
    }

    public int getPoolMaxTotal() {
        return poolMaxTotal;
    }

    public int getPoolMaxIdle() {
        return poolMaxIdle;
    }

    public int getPoolMinIdle() {
        return poolMinIdle;
    }

    public int getPoolTimeout() {
        return poolTimeout;
    }

    @NotNull
    public String getServerId() {
        return serverId;
    }

    public int getClanCacheTtl() {
        return clanCacheTtl;
    }

    public int getPlayerCacheTtl() {
        return playerCacheTtl;
    }

    public int getLockBankTimeout() {
        return lockBankTimeout;
    }

    public int getLockDisbandTimeout() {
        return lockDisbandTimeout;
    }

    public int getLockDefaultTimeout() {
        return lockDefaultTimeout;
    }

    public int getRequestTtl() {
        return requestTtl;
    }

    public int getVoteTtl() {
        return voteTtl;
    }

    public int getReconnectDelay() {
        return reconnectDelay;
    }

    public int getReconnectMaxAttempts() {
        return reconnectMaxAttempts;
    }
    
    /**
     * Returns the kill delay in minutes from existing config.
     */
    public int getKillDelayMinutes() {
        return plugin.getConfig().getInt("kill-weights.delay-between-kills", 60);
    }
}
