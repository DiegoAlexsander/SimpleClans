package net.sacredlabyrinth.phaed.simpleclans.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage;
import net.sacredlabyrinth.phaed.simpleclans.redis.RedisManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static net.sacredlabyrinth.phaed.simpleclans.ClanPlayer.Channel;

/**
 * ProxyManager implementation that uses Redis for cross-server communication.
 * <p>
 * This is an alternative to BungeeManager that doesn't require BungeeCord.
 * It uses Redis Pub/Sub for real-time communication and Redis data structures
 * for player tracking.
 * </p>
 * 
 * @since 2.0
 */
public class RedisProxyManager implements ProxyManager {

    private static final Gson GSON = new Gson();
    
    private final SimpleClans plugin;
    private final RedisManager redisManager;
    private final String serverId;
    
    // Cache of online players across all servers (updated via Pub/Sub)
    // Key: lowercase name for case-insensitive lookup, Value: PlayerInfo with name and server
    private final Map<String, PlayerInfo> globalOnlinePlayers = new ConcurrentHashMap<>();
    
    /**
     * Holds information about a remote player.
     */
    public static class PlayerInfo {
        private final String name;
        private final String server;
        
        public PlayerInfo(String name, String server) {
            this.name = name;
            this.server = server;
        }
        
        public String getName() {
            return name;
        }
        
        public String getServer() {
            return server;
        }
    }

    public RedisProxyManager(@NotNull SimpleClans plugin, @NotNull RedisManager redisManager) {
        this.plugin = plugin;
        this.redisManager = redisManager;
        this.serverId = redisManager.getServerId();
        
        plugin.getLogger().info("[Redis] RedisProxyManager initialized with server-id: " + serverId);
    }

    @Override
    public String getServerName() {
        return serverId;
    }

    @Override
    public boolean isOnline(String playerName) {
        // Check local first
        if (Bukkit.getOnlinePlayers().stream().anyMatch(p -> p.getName().equalsIgnoreCase(playerName))) {
            return true;
        }
        // Check global cache
        return globalOnlinePlayers.containsKey(playerName.toLowerCase());
    }

    @Override
    public void sendMessage(SCMessage message) {
        // Chat messages are handled by RedisChatHandler
        // This method is called by ProxyChatHandler which we're replacing
        if (!redisManager.isInitialized()) {
            return;
        }
        
        ClanPlayer sender = message.getSender();
        if (sender == null || sender.getClan() == null) {
            return;
        }
        
        // Build the formatted message
        String format = getFormatForChannel(message.getChannel());
        String formattedMessage = plugin.getChatManager().parseChatFormat(format, message);
        
        // Create JSON payload
        JsonObject json = new JsonObject();
        json.addProperty("type", message.getChannel() == Channel.ALLY ? "ally" : "clan");
        json.addProperty("clanTag", sender.getClan().getTag());
        json.addProperty("senderName", sender.getName());
        json.addProperty("senderUuid", sender.getUniqueId().toString());
        json.addProperty("message", formattedMessage);
        json.addProperty("rawMessage", message.getContent());
        
        redisManager.publish(RedisManager.CHANNEL_CHAT, GSON.toJson(json));
    }

    @Override
    public void sendMessage(@NotNull String target, @NotNull String message) {
        if (message.isEmpty()) {
            return;
        }

        if ("ALL".equals(target)) {
            // Broadcast to all servers
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
            redisManager.publish(RedisManager.CHANNEL_BROADCAST, message);
            return;
        }

        // Check if player is on this server
        Player player = Bukkit.getPlayerExact(target);
        if (player != null) {
            player.sendMessage(message);
            return;
        }

        // Send private message via Redis
        sendPrivateMessage(target, message);
    }

    @Override
    public void sendUpdate(Clan clan) {
        if (!redisManager.isInitialized()) {
            return;
        }
        // Invalidate cache on other servers
        redisManager.invalidate("clan", clan.getTag());
    }

    @Override
    public void sendUpdate(ClanPlayer cp) {
        if (!redisManager.isInitialized()) {
            return;
        }
        // Invalidate cache on other servers
        redisManager.invalidate("player", cp.getUniqueId().toString());
    }

    @Override
    public void sendDelete(Clan clan) {
        if (!redisManager.isInitialized()) {
            return;
        }
        // Invalidate cache on other servers
        redisManager.invalidate("clan:delete", clan.getTag());
    }

    @Override
    public void sendDelete(ClanPlayer cp) {
        if (!redisManager.isInitialized()) {
            return;
        }
        // Invalidate cache on other servers
        redisManager.invalidate("player:delete", cp.getUniqueId().toString());
    }

    /**
     * Sends a private message to a player on another server.
     */
    private void sendPrivateMessage(@NotNull String playerName, @NotNull String message) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "private");
        json.addProperty("targetName", playerName);
        json.addProperty("message", message);
        
        redisManager.publish(RedisManager.CHANNEL_CHAT, GSON.toJson(json));
    }

    /**
     * Updates the global online players set.
     * Called when receiving player join/quit notifications.
     * 
     * @param playerName the player name
     * @param serverName the server the player is on
     */
    public void addGlobalPlayer(@NotNull String playerName, @NotNull String serverName) {
        globalOnlinePlayers.put(playerName.toLowerCase(), new PlayerInfo(playerName, serverName));
    }

    /**
     * Removes a player from the global online set.
     */
    public void removeGlobalPlayer(@NotNull String playerName) {
        globalOnlinePlayers.remove(playerName.toLowerCase());
    }

    /**
     * Clears players from a specific server.
     * Used when a server goes offline or during sync.
     * 
     * @param serverName the server to clear players from
     */
    public void clearPlayersFromServer(@NotNull String serverName) {
        globalOnlinePlayers.entrySet().removeIf(entry -> serverName.equals(entry.getValue().getServer()));
    }

    /**
     * Gets all globally online player names (from other servers only).
     * Does not include players on the local server.
     * 
     * @return set of player names from other servers (with correct capitalization)
     */
    @NotNull
    public Set<String> getGlobalOnlinePlayers() {
        Set<String> names = new HashSet<>();
        for (PlayerInfo info : globalOnlinePlayers.values()) {
            names.add(info.getName());
        }
        return names;
    }
    
    /**
     * Gets the server a remote player is on.
     * 
     * @param playerName the player name (case-insensitive)
     * @return the server name, or null if not found
     */
    @Nullable
    public String getPlayerServer(@NotNull String playerName) {
        PlayerInfo info = globalOnlinePlayers.get(playerName.toLowerCase());
        return info != null ? info.getServer() : null;
    }

    /**
     * Gets all online player names across all servers.
     * Includes both local and remote players.
     * 
     * @return set of all online player names (with correct capitalization)
     */
    @NotNull
    public Set<String> getAllOnlinePlayers() {
        Set<String> all = new HashSet<>();
        for (PlayerInfo info : globalOnlinePlayers.values()) {
            all.add(info.getName());
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            all.add(p.getName());
        }
        return all;
    }
    
    /**
     * Gets the local server ID.
     * 
     * @return the server ID from config
     */
    @NotNull
    public String getLocalServerId() {
        return serverId;
    }

    private String getFormatForChannel(Channel channel) {
        if (channel == Channel.ALLY) {
            return plugin.getSettingsManager().getColored(
                    net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.ALLYCHAT_FORMAT);
        }
        return plugin.getSettingsManager().getColored(
                net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.CLANCHAT_FORMAT);
    }
}
