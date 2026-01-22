package net.sacredlabyrinth.phaed.simpleclans.redis;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.redis.cache.ClanCache;
import net.sacredlabyrinth.phaed.simpleclans.redis.cache.PlayerCache;
import net.sacredlabyrinth.phaed.simpleclans.redis.lock.DistributedLock;
import net.sacredlabyrinth.phaed.simpleclans.redis.lock.RedisLock;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.MessageHandler;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.RedisPublisher;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.RedisSubscriber;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers.BanHandler;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers.BroadcastHandler;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers.ChatHandler;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers.InvalidateHandler;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers.OnlinePlayersHandler;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers.RequestHandler;
import net.sacredlabyrinth.phaed.simpleclans.redis.request.RedisRequestStorage;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Main Redis manager for SimpleClans multi-server synchronization.
 * Handles connection pooling, pub/sub, caching, and provides access to sub-managers.
 */
public class RedisManager {

    // Pub/Sub Channels
    public static final String CHANNEL_INVALIDATE = "simpleclans:invalidate";
    public static final String CHANNEL_UPDATE = "simpleclans:update";
    public static final String CHANNEL_CHAT = "simpleclans:chat";
    public static final String CHANNEL_BROADCAST = "simpleclans:broadcast";
    public static final String CHANNEL_REQUEST = "simpleclans:request";
    public static final String CHANNEL_ONLINE = "simpleclans:online";
    public static final String CHANNEL_BAN = "simpleclans:ban";

    private final SimpleClans plugin;
    private final RedisConfiguration config;
    
    private JedisPool jedisPool;
    private RedisPublisher publisher;
    private RedisSubscriber subscriber;
    private Thread subscriberThread;
    
    // Caches
    private ClanCache clanCache;
    private PlayerCache playerCache;
    
    // Request storage
    private RedisRequestStorage requestStorage;
    
    private final Map<String, MessageHandler> messageHandlers = new ConcurrentHashMap<>();
    
    private volatile boolean initialized = false;

    public RedisManager(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
        this.config = new RedisConfiguration(plugin);
    }

    /**
     * Initializes the Redis connection and starts the subscriber thread.
     * 
     * @throws RedisConnectionException if connection fails
     */
    public void initialize() throws RedisConnectionException {
        config.load();
        
        if (!config.isEnabled()) {
            plugin.getLogger().info("[Redis] Redis is disabled in config");
            return;
        }
        
        plugin.getLogger().info("[Redis] Initializing connection to " + config.getHost() + ":" + config.getPort());
        
        try {
            // Configure pool
            GenericObjectPoolConfig<Jedis> poolConfig = new GenericObjectPoolConfig<>();
            poolConfig.setMaxTotal(config.getPoolMaxTotal());
            poolConfig.setMaxIdle(config.getPoolMaxIdle());
            poolConfig.setMinIdle(config.getPoolMinIdle());
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestWhileIdle(true);
            poolConfig.setBlockWhenExhausted(true);
            
            // Configure client (supports username for Redis 6.0+ ACL)
            DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(config.getPoolTimeout())
                    .socketTimeoutMillis(config.getPoolTimeout())
                    .ssl(config.isSsl());
            
            // Authentication
            String username = config.getUsername();
            String password = config.getPassword();
            
            if (password != null) {
                if (username != null) {
                    // Redis 6.0+ with ACL (username + password)
                    clientConfigBuilder.user(username).password(password);
                    plugin.getLogger().info("[Redis] Using ACL authentication (username: " + username + ")");
                } else {
                    // Redis < 6.0 (password only)
                    clientConfigBuilder.password(password);
                    plugin.getLogger().info("[Redis] Using password authentication");
                }
            } else {
                plugin.getLogger().info("[Redis] Connecting without authentication");
            }
            
            // Create pool with HostAndPort
            HostAndPort hostAndPort = new HostAndPort(config.getHost(), config.getPort());
            JedisClientConfig clientConfig = clientConfigBuilder.build();
            
            jedisPool = new JedisPool(poolConfig, hostAndPort, clientConfig);
            
            // Test connection (use pool directly since initialized is still false)
            try (Jedis jedis = jedisPool.getResource()) {
                String pong = jedis.ping();
                plugin.getLogger().info("[Redis] Connection successful: " + pong);
            }
            
            // Initialize publisher
            publisher = new RedisPublisher(this, config.getServerId());
            
            // Initialize caches
            clanCache = new ClanCache(this);
            playerCache = new PlayerCache(this);
            plugin.getLogger().info("[Redis] Initialized caches (clan TTL: " + config.getClanCacheTtl() + 
                    "s, player TTL: " + config.getPlayerCacheTtl() + "s)");
            
            // Initialize request storage
            requestStorage = new RedisRequestStorage(this, plugin);
            plugin.getLogger().info("[Redis] Initialized request storage (TTL: " + requestStorage.getTtlSeconds() + "s)");
            
            // Register message handlers
            registerDefaultHandlers();
            
            // Initialize subscriber and start thread
            subscriber = new RedisSubscriber(this, plugin, config.getServerId(), messageHandlers);
            subscriberThread = new Thread(subscriber, "SimpleClans-RedisSubscriber");
            subscriberThread.setDaemon(true);
            subscriberThread.start();
            
            initialized = true;
            plugin.getLogger().info("[Redis] Initialized successfully with server-id: " + config.getServerId());
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[Redis] Failed to initialize", e);
            throw new RedisConnectionException("Failed to connect to Redis: " + e.getMessage(), e);
        }
    }

    /**
     * Shuts down Redis connections and stops the subscriber thread.
     */
    public void shutdown() {
        if (!initialized) {
            return;
        }
        
        plugin.getLogger().info("[Redis] Shutting down...");
        
        // Stop subscriber
        if (subscriber != null) {
            subscriber.shutdown();
        }
        
        // Interrupt subscriber thread
        if (subscriberThread != null && subscriberThread.isAlive()) {
            subscriberThread.interrupt();
            try {
                subscriberThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Close pool
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
        
        initialized = false;
        plugin.getLogger().info("[Redis] Shutdown complete");
    }

    /**
     * Gets a connection from the pool.
     * The caller is responsible for closing the connection (use try-with-resources).
     * 
     * @return Jedis connection
     * @throws IllegalStateException if not initialized
     */
    @NotNull
    public Jedis getConnection() {
        if (!initialized || jedisPool == null) {
            throw new IllegalStateException("RedisManager is not initialized");
        }
        return jedisPool.getResource();
    }

    /**
     * Publishes a message to a channel.
     * Automatically prefixes with server-id.
     * 
     * @param channel the channel to publish to
     * @param payload the message payload
     */
    public void publish(@NotNull String channel, @NotNull String payload) {
        if (!initialized) {
            return;
        }
        publisher.publish(channel, payload);
    }

    /**
     * Publishes an invalidation message.
     * 
     * @param type the type of invalidation (clan, player, etc.)
     * @param id the identifier of the invalidated entity
     */
    public void invalidate(@NotNull String type, @NotNull String id) {
        publish(CHANNEL_INVALIDATE, type + ":" + id);
    }

    /**
     * Registers a message handler for a channel.
     * 
     * @param channel the channel to handle
     * @param handler the handler
     */
    public void registerHandler(@NotNull String channel, @NotNull MessageHandler handler) {
        messageHandlers.put(channel, handler);
    }

    /**
     * Registers all default message handlers.
     */
    private void registerDefaultHandlers() {
        // Cache invalidation handler - processes clan/player cache updates
        registerHandler(CHANNEL_INVALIDATE, new InvalidateHandler(plugin));
        
        // Chat handler - processes cross-server clan/ally chat
        registerHandler(CHANNEL_CHAT, new ChatHandler(plugin));
        
        // Broadcast handler - processes global broadcast messages
        registerHandler(CHANNEL_BROADCAST, new BroadcastHandler(plugin));
        
        // Request handler - processes cross-server request notifications
        registerHandler(CHANNEL_REQUEST, new RequestHandler(plugin));
        
        // Online players handler - synchronizes online players across servers
        registerHandler(CHANNEL_ONLINE, new OnlinePlayersHandler(plugin));
        
        // Ban handler - synchronizes bans across servers
        registerHandler(CHANNEL_BAN, new BanHandler(plugin));
        
        plugin.getLogger().info("[Redis] Registered " + messageHandlers.size() + " message handlers");
    }

    /**
     * Acquires a distributed lock.
     * 
     * @param resource the resource to lock
     * @param timeoutMs the lock timeout in milliseconds
     * @return the lock (must be released/closed)
     */
    @NotNull
    public DistributedLock acquireLock(@NotNull String resource, long timeoutMs) {
        return new RedisLock(this, resource, timeoutMs);
    }

    /**
     * Acquires a lock for bank operations.
     * Uses the configured bank timeout.
     * 
     * @param clanTag the clan tag
     * @return the lock
     */
    @NotNull
    public DistributedLock acquireBankLock(@NotNull String clanTag) {
        return acquireLock("bank:" + clanTag, config.getLockBankTimeout());
    }

    /**
     * Acquires a lock for disband operations.
     * Uses the configured disband timeout.
     * 
     * @param clanTag the clan tag
     * @return the lock
     */
    @NotNull
    public DistributedLock acquireDisbandLock(@NotNull String clanTag) {
        return acquireLock("disband:" + clanTag, config.getLockDisbandTimeout());
    }

    // ==================== Getters ====================

    public boolean isInitialized() {
        return initialized;
    }

    @NotNull
    public SimpleClans getPlugin() {
        return plugin;
    }

    @NotNull
    public RedisConfiguration getConfig() {
        return config;
    }

    @NotNull
    public String getServerId() {
        return config.getServerId();
    }

    @Nullable
    public JedisPool getJedisPool() {
        return jedisPool;
    }

    /**
     * Gets the clan cache.
     * 
     * @return the clan cache, or null if not initialized
     */
    @Nullable
    public ClanCache getClanCache() {
        return clanCache;
    }

    /**
     * Gets the player cache.
     * 
     * @return the player cache, or null if not initialized
     */
    @Nullable
    public PlayerCache getPlayerCache() {
        return playerCache;
    }

    /**
     * Gets the request storage.
     * 
     * @return the request storage, or null if not initialized
     */
    @Nullable
    public RedisRequestStorage getRequestStorage() {
        return requestStorage;
    }

    // ==================== Online Players Sync ====================

    /**
     * Publishes a player join event to other servers.
     * 
     * @param playerName the name of the player who joined
     */
    public void publishPlayerJoin(@NotNull String playerName) {
        if (!initialized) return;
        publish(CHANNEL_ONLINE, "{\"type\":\"join\",\"player\":\"" + playerName + "\",\"server\":\"" + config.getServerId() + "\"}");
    }

    /**
     * Publishes a player quit event to other servers.
     * 
     * @param playerName the name of the player who quit
     */
    public void publishPlayerQuit(@NotNull String playerName) {
        if (!initialized) return;
        publish(CHANNEL_ONLINE, "{\"type\":\"quit\",\"player\":\"" + playerName + "\",\"server\":\"" + config.getServerId() + "\"}");
    }

    /**
     * Publishes the local server's player list to other servers.
     * Called when a server starts or when another server requests sync.
     */
    public void publishLocalPlayersSync() {
        if (!initialized) return;
        
        StringBuilder sb = new StringBuilder("{\"type\":\"sync\",\"server\":\"" + config.getServerId() + "\",\"players\":[");
        boolean first = true;
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!first) sb.append(",");
            sb.append("\"").append(p.getName()).append("\"");
            first = false;
        }
        sb.append("]}");
        
        publish(CHANNEL_ONLINE, sb.toString());
    }

    /**
     * Requests all servers to send their player lists.
     * Called when this server starts.
     */
    public void requestPlayersSync() {
        if (!initialized) return;
        publish(CHANNEL_ONLINE, "{\"type\":\"request_sync\"}");
    }

    // ==================== Ban Sync ====================

    /**
     * Publishes a ban event to other servers.
     * 
     * @param uuid the UUID of the banned player
     */
    public void publishBan(@NotNull java.util.UUID uuid) {
        if (!initialized) return;
        publish(CHANNEL_BAN, "{\"type\":\"ban\",\"uuid\":\"" + uuid.toString() + "\"}");
    }

    /**
     * Publishes an unban event to other servers.
     * 
     * @param uuid the UUID of the unbanned player
     */
    public void publishUnban(@NotNull java.util.UUID uuid) {
        if (!initialized) return;
        publish(CHANNEL_BAN, "{\"type\":\"unban\",\"uuid\":\"" + uuid.toString() + "\"}");
    }

    /**
     * Gets a Jedis resource from the pool safely.
     * Returns null if not initialized instead of throwing an exception.
     * The caller is responsible for closing the connection (use try-with-resources).
     * 
     * @return Jedis connection or null
     */
    @Nullable
    public Jedis getResource() {
        if (!initialized || jedisPool == null) {
            return null;
        }
        try {
            return jedisPool.getResource();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Redis] Failed to get resource from pool", e);
            return null;
        }
    }

    /**
     * Exception thrown when Redis connection fails.
     */
    public static class RedisConnectionException extends Exception {
        public RedisConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
