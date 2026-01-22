package net.sacredlabyrinth.phaed.simpleclans.chat.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.chat.ChatHandler;
import net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage;
import net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage.Source;
import net.sacredlabyrinth.phaed.simpleclans.redis.RedisManager;
import net.sacredlabyrinth.phaed.simpleclans.utils.ChatUtils;

import static net.sacredlabyrinth.phaed.simpleclans.ClanPlayer.Channel;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField;

/**
 * Chat handler that sends clan/ally chat messages to other servers via Redis.
 * <p>
 * This handler publishes chat messages to the Redis Pub/Sub channel,
 * allowing all connected servers to receive and display the message
 * to their local clan members.
 * </p>
 * 
 * @since 2.0
 */
public class RedisChatHandler implements ChatHandler {

    private static final Gson GSON = new Gson();

    @Override
    public void sendMessage(SCMessage message) {
        RedisManager redisManager = plugin.getRedisManager();
        if (redisManager == null || !redisManager.isInitialized()) {
            return;
        }
        
        ClanPlayer sender = message.getSender();
        if (sender == null || sender.getClan() == null) {
            return;
        }
        
        // Build the formatted message to send
        String format = getFormatForChannel(message.getChannel());
        String formattedMessage = plugin.getChatManager().parseChatFormat(format, message);
        
        // Build the spy message
        SCMessage spyMessage = message.clone();
        spyMessage.setContent(ChatUtils.stripColors(message.getContent()));
        String spyFormat = getSpyFormatForChannel(message.getChannel());
        String formattedSpyMessage = plugin.getChatManager().parseChatFormat(spyFormat, spyMessage);
        
        // Create JSON payload
        JsonObject json = new JsonObject();
        json.addProperty("type", message.getChannel() == Channel.ALLY ? "ally" : "clan");
        json.addProperty("clanTag", sender.getClan().getTag());
        json.addProperty("senderName", sender.getName());
        json.addProperty("senderUuid", sender.getUniqueId().toString());
        json.addProperty("message", formattedMessage);
        json.addProperty("rawMessage", message.getContent());
        json.addProperty("spyMessage", formattedSpyMessage);
        
        // Publish to Redis
        redisManager.publish(RedisManager.CHANNEL_CHAT, GSON.toJson(json));
    }

    @Override
    public boolean canHandle(Source source) {
        // Only handle SPIGOT source (not PROXY or DISCORD) and only when Redis is enabled
        if (source != Source.SPIGOT) {
            return false;
        }
        
        // Check if Redis is actually initialized
        RedisManager redisManager = plugin.getRedisManager();
        return redisManager != null && redisManager.isInitialized();
    }

    private String getFormatForChannel(Channel channel) {
        if (channel == Channel.ALLY) {
            return settingsManager.getColored(ConfigField.ALLYCHAT_FORMAT);
        }
        return settingsManager.getColored(ConfigField.CLANCHAT_FORMAT);
    }
    
    private String getSpyFormatForChannel(Channel channel) {
        if (channel == Channel.ALLY) {
            return settingsManager.getString(ConfigField.ALLYCHAT_SPYFORMAT);
        }
        return settingsManager.getString(ConfigField.CLANCHAT_SPYFORMAT);
    }
}
