package net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.sacredlabyrinth.phaed.simpleclans.ChatBlock;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.MessageHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.logging.Level;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static org.bukkit.ChatColor.AQUA;

/**
 * Handles ban/unban synchronization across servers.
 */
public class BanHandler implements MessageHandler {

    private final SimpleClans plugin;

    public BanHandler(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(@NotNull String payload) {
        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            String type = json.get("type").getAsString();
            String uuidStr = json.get("uuid").getAsString();
            
            if (type == null || uuidStr == null) {
                return;
            }
            
            UUID uuid = UUID.fromString(uuidStr);
            Player player = Bukkit.getPlayer(uuid);
            
            switch (type) {
                case "ban":
                    // Add to local banned list without publishing again
                    plugin.getSettingsManager().addBannedLocal(uuid);
                    plugin.getLogger().info("[Redis] Received ban sync for " + uuidStr);
                    
                    // Notify player if online
                    if (player != null) {
                        ChatBlock.sendMessage(player, AQUA + lang("you.banned", player));
                    }
                    break;
                case "unban":
                    // Remove from local banned list without publishing again
                    plugin.getSettingsManager().removeBannedLocal(uuid);
                    plugin.getLogger().info("[Redis] Received unban sync for " + uuidStr);
                    
                    // Notify player if online
                    if (player != null) {
                        ChatBlock.sendMessage(player, AQUA + lang("you.have.been.unbanned.from.clan.commands", player));
                    }
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Redis] Failed to parse ban message: " + payload, e);
        }
    }
}
