package net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.MessageHandler;
import net.sacredlabyrinth.phaed.simpleclans.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Handles broadcast messages from other servers.
 * Sends the message to all online players on this server.
 */
public class BroadcastHandler implements MessageHandler {

    private final SimpleClans plugin;

    public BroadcastHandler(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(@NotNull String payload) {
        // Translate color codes
        String coloredMessage = ChatUtils.parseColors(payload);
        
        // Send to all online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(coloredMessage);
        }
        
        // Also log to console
        plugin.getLogger().info("[Redis Broadcast] " + payload);
    }
}
