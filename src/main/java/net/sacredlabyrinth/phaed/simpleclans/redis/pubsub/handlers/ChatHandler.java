package net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.MessageHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Handles chat messages from other servers.
 * Supports clan chat, ally chat, and private messages.
 * 
 * <p>Message format (JSON):</p>
 * <pre>
 * {
 *   "type": "clan|ally|private",
 *   "clanTag": "ABC",       // for clan/ally chat
 *   "senderName": "Player",
 *   "senderUuid": "uuid",
 *   "targetName": "Target", // for private messages
 *   "message": "Hello!",
 *   "spyMessage": "[Spy] ..."  // formatted spy message
 * }
 * </pre>
 */
public class ChatHandler implements MessageHandler {

    private static final String SPY_PERMISSION = "simpleclans.admin.all-seeing-eye";
    
    private final SimpleClans plugin;
    private final Gson gson;

    public ChatHandler(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
        this.gson = new Gson();
    }

    @Override
    public void handle(@NotNull String payload) {
        try {
            JsonObject json = gson.fromJson(payload, JsonObject.class);
            String type = json.has("type") ? json.get("type").getAsString() : "clan";
            
            switch (type) {
                case "clan":
                    handleClanChat(json);
                    break;
                case "ally":
                    handleAllyChat(json);
                    break;
                case "private":
                    handlePrivateMessage(json);
                    break;
                default:
                    plugin.getLogger().fine("[Redis] Unknown chat type: " + type);
            }
        } catch (JsonSyntaxException e) {
            plugin.getLogger().log(Level.WARNING, "[Redis] Invalid chat message format: " + payload, e);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Redis] Error handling chat message", e);
        }
    }

    private void handleClanChat(JsonObject json) {
        String clanTag = getStringOrNull(json, "clanTag");
        String senderName = getStringOrNull(json, "senderName");
        String message = getStringOrNull(json, "message");
        String spyMessage = getStringOrNull(json, "spyMessage");
        
        if (clanTag == null || message == null) {
            return;
        }
        
        Clan clan = plugin.getClanManager().getClan(clanTag);
        if (clan == null) {
            return;
        }
        
        // Track who received the message (to avoid duplicate spy messages)
        Set<UUID> receivers = new HashSet<>();
        
        // Send to online clan members on this server
        for (ClanPlayer member : clan.getOnlineMembers()) {
            Player player = member.toPlayer();
            if (player != null) {
                player.sendMessage(message);
                receivers.add(player.getUniqueId());
            }
        }
        
        // Send to local spies (if spy message is provided)
        if (spyMessage != null) {
            sendToSpies(spyMessage, receivers);
        }
        
        plugin.getLogger().fine("[Redis] Forwarded clan chat from " + senderName + " to clan " + clanTag);
    }

    private void handleAllyChat(JsonObject json) {
        String clanTag = getStringOrNull(json, "clanTag");
        String senderName = getStringOrNull(json, "senderName");
        String message = getStringOrNull(json, "message");
        String spyMessage = getStringOrNull(json, "spyMessage");
        
        if (clanTag == null || message == null) {
            return;
        }
        
        Clan senderClan = plugin.getClanManager().getClan(clanTag);
        if (senderClan == null) {
            return;
        }
        
        // Track who received the message (to avoid duplicate spy messages)
        Set<UUID> receivers = new HashSet<>();
        
        // Send to online members of sender clan and all allied clans
        for (ClanPlayer member : senderClan.getOnlineMembers()) {
            Player player = member.toPlayer();
            if (player != null) {
                player.sendMessage(message);
                receivers.add(player.getUniqueId());
            }
        }
        
        for (String allyTag : senderClan.getAllies()) {
            Clan allyClan = plugin.getClanManager().getClan(allyTag);
            if (allyClan != null) {
                for (ClanPlayer member : allyClan.getOnlineMembers()) {
                    Player player = member.toPlayer();
                    if (player != null) {
                        player.sendMessage(message);
                        receivers.add(player.getUniqueId());
                    }
                }
            }
        }
        
        // Send to local spies (if spy message is provided)
        if (spyMessage != null) {
            sendToSpies(spyMessage, receivers);
        }
        
        plugin.getLogger().fine("[Redis] Forwarded ally chat from " + senderName);
    }

    private void handlePrivateMessage(JsonObject json) {
        String targetName = getStringOrNull(json, "targetName");
        String message = getStringOrNull(json, "message");
        
        if (targetName == null || message == null) {
            return;
        }
        
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null && target.isOnline()) {
            // Use toBaseComponents to process %accept% and %deny% placeholders into clickable links
            target.spigot().sendMessage(
                    net.sacredlabyrinth.phaed.simpleclans.utils.ChatUtils.toBaseComponents(target, message));
            plugin.getLogger().fine("[Redis] Delivered private message to " + targetName);
        }
    }
    
    /**
     * Sends a spy message to all local players with the spy permission.
     * 
     * @param spyMessage the formatted spy message
     * @param exclude UUIDs of players who already received the message (clan members)
     */
    private void sendToSpies(String spyMessage, Set<UUID> exclude) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Skip if already received as clan member
            if (exclude.contains(player.getUniqueId())) {
                continue;
            }
            
            // Check if has spy permission
            if (plugin.getPermissionsManager().has(player, SPY_PERMISSION)) {
                // Check if player is not muted
                ClanPlayer cp = plugin.getClanManager().getClanPlayer(player.getUniqueId());
                if (cp == null || !cp.isMuted()) {
                    player.sendMessage(spyMessage);
                }
            }
        }
    }

    private String getStringOrNull(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() 
                ? json.get(key).getAsString() 
                : null;
    }
}
