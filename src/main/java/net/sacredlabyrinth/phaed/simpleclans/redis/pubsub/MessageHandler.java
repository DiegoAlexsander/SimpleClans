package net.sacredlabyrinth.phaed.simpleclans.redis.pubsub;

import org.jetbrains.annotations.NotNull;

/**
 * Interface for handling messages received from Redis Pub/Sub.
 */
@FunctionalInterface
public interface MessageHandler {
    
    /**
     * Handles a message received from Redis.
     * This method is called on the main Bukkit thread.
     * 
     * @param payload the message payload (without server-id prefix)
     */
    void handle(@NotNull String payload);
}
