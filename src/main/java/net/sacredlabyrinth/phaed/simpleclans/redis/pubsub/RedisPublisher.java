package net.sacredlabyrinth.phaed.simpleclans.redis.pubsub;

import net.sacredlabyrinth.phaed.simpleclans.redis.RedisManager;
import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.Jedis;

import java.util.logging.Level;

/**
 * Publishes messages to Redis Pub/Sub channels.
 * All messages are prefixed with the server-id to allow filtering.
 */
public class RedisPublisher {

    private static final char SEPARATOR = '|';
    
    private final RedisManager redis;
    private final String serverId;

    public RedisPublisher(@NotNull RedisManager redis, @NotNull String serverId) {
        this.redis = redis;
        this.serverId = serverId;
    }

    /**
     * Publishes a message to a channel.
     * The message is prefixed with the server-id: "serverId|payload"
     * 
     * @param channel the channel to publish to
     * @param payload the message payload
     */
    public void publish(@NotNull String channel, @NotNull String payload) {
        String message = serverId + SEPARATOR + payload;
        
        try (Jedis jedis = redis.getConnection()) {
            jedis.publish(channel, message);
        } catch (Exception e) {
            redis.getPlugin().getLogger().log(Level.WARNING, 
                    "[Redis] Failed to publish message to channel " + channel, e);
        }
    }

    /**
     * Publishes a message without server-id prefix.
     * Use with caution - the message will be processed by all servers including sender.
     * 
     * @param channel the channel to publish to
     * @param payload the message payload
     */
    public void publishRaw(@NotNull String channel, @NotNull String payload) {
        try (Jedis jedis = redis.getConnection()) {
            jedis.publish(channel, payload);
        } catch (Exception e) {
            redis.getPlugin().getLogger().log(Level.WARNING,
                    "[Redis] Failed to publish raw message to channel " + channel, e);
        }
    }
}
