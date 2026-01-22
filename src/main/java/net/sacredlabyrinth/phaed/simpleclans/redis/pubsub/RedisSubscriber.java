package net.sacredlabyrinth.phaed.simpleclans.redis.pubsub;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.redis.RedisManager;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.logging.Level;

/**
 * Subscribes to Redis Pub/Sub channels and dispatches messages to handlers.
 * Runs in a separate thread and automatically reconnects on failure.
 */
public class RedisSubscriber extends JedisPubSub implements Runnable {

    private static final char SEPARATOR = '|';
    
    private final RedisManager redis;
    private final SimpleClans plugin;
    private final String serverId;
    private final Map<String, MessageHandler> handlers;
    
    private volatile boolean running = true;
    private int reconnectAttempts = 0;

    public RedisSubscriber(@NotNull RedisManager redis, 
                           @NotNull SimpleClans plugin,
                           @NotNull String serverId,
                           @NotNull Map<String, MessageHandler> handlers) {
        this.redis = redis;
        this.plugin = plugin;
        this.serverId = serverId;
        this.handlers = handlers;
    }

    @Override
    public void onMessage(String channel, String message) {
        try {
            // Check if message is from this server
            if (isFromThisServer(message)) {
                return;
            }
            
            // Extract payload (remove server-id prefix)
            String payload = extractPayload(message);
            
            // Find handler for this channel
            MessageHandler handler = handlers.get(channel);
            if (handler != null) {
                // Execute on main Bukkit thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        handler.handle(payload);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING,
                                "[Redis] Error handling message on channel " + channel, e);
                    }
                });
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "[Redis] Error processing message from channel " + channel, e);
        }
    }

    @Override
    public void onSubscribe(String channel, int subscribedChannels) {
        plugin.getLogger().info("[Redis] Subscribed to channel: " + channel);
    }

    @Override
    public void onUnsubscribe(String channel, int subscribedChannels) {
        plugin.getLogger().info("[Redis] Unsubscribed from channel: " + channel);
    }

    /**
     * Main loop - subscribes to channels and reconnects on failure.
     */
    @Override
    public void run() {
        while (running) {
            try (Jedis jedis = redis.getConnection()) {
                reconnectAttempts = 0;
                
                plugin.getLogger().info("[Redis] Subscribing to channels...");
                
                // Subscribe to all channels (this blocks until unsubscribed)
                jedis.subscribe(this,
                        RedisManager.CHANNEL_INVALIDATE,
                        RedisManager.CHANNEL_UPDATE,
                        RedisManager.CHANNEL_CHAT,
                        RedisManager.CHANNEL_BROADCAST,
                        RedisManager.CHANNEL_REQUEST,
                        RedisManager.CHANNEL_ONLINE,
                        RedisManager.CHANNEL_BAN
                );
                
            } catch (Exception e) {
                if (running) {
                    reconnectAttempts++;
                    int maxAttempts = redis.getConfig().getReconnectMaxAttempts();
                    
                    if (maxAttempts > 0 && reconnectAttempts >= maxAttempts) {
                        plugin.getLogger().severe("[Redis] Max reconnection attempts reached (" 
                                + maxAttempts + "). Giving up.");
                        running = false;
                        break;
                    }
                    
                    plugin.getLogger().warning("[Redis] Subscriber disconnected, reconnecting in " 
                            + (redis.getConfig().getReconnectDelay() / 1000) + "s... (attempt " 
                            + reconnectAttempts + ")");
                    
                    try {
                        Thread.sleep(redis.getConfig().getReconnectDelay());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        plugin.getLogger().info("[Redis] Subscriber thread stopped");
    }

    /**
     * Stops the subscriber and unsubscribes from all channels.
     */
    public void shutdown() {
        running = false;
        
        try {
            if (isSubscribed()) {
                unsubscribe();
            }
        } catch (Exception e) {
            // Ignore - we're shutting down anyway
        }
    }

    /**
     * Checks if the message was sent by this server.
     * Message format: "serverId|payload"
     * 
     * @param message the full message
     * @return true if this server sent the message
     */
    private boolean isFromThisServer(@NotNull String message) {
        int separatorIndex = message.indexOf(SEPARATOR);
        if (separatorIndex == -1) {
            // No separator - process anyway (legacy or raw message)
            return false;
        }
        
        String messageServerId = message.substring(0, separatorIndex);
        return serverId.equals(messageServerId);
    }

    /**
     * Extracts the payload from the message (removes server-id prefix).
     * 
     * @param message the full message
     * @return the payload
     */
    @NotNull
    private String extractPayload(@NotNull String message) {
        int separatorIndex = message.indexOf(SEPARATOR);
        if (separatorIndex == -1) {
            return message;
        }
        return message.substring(separatorIndex + 1);
    }
}
