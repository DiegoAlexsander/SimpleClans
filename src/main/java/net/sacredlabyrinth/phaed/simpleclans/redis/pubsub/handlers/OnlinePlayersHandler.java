package net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.proxy.RedisProxyManager;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.MessageHandler;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * Handles online player synchronization across servers.
 * 
 * <p>Message formats:</p>
 * <ul>
 *   <li>{"type":"join","player":"PlayerName"} - Player joined a server</li>
 *   <li>{"type":"quit","player":"PlayerName"} - Player left a server</li>
 *   <li>{"type":"sync","players":["Player1","Player2",...]} - Full player list sync</li>
 *   <li>{"type":"request_sync"} - Request all servers to send their player lists</li>
 * </ul>
 */
public class OnlinePlayersHandler implements MessageHandler {

    private static final Gson GSON = new Gson();
    
    private final SimpleClans plugin;

    public OnlinePlayersHandler(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(@NotNull String payload) {
        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            String type = json.get("type").getAsString();
            
            switch (type) {
                case "join":
                    handleJoin(json);
                    break;
                case "quit":
                    handleQuit(json);
                    break;
                case "sync":
                    handleSync(json);
                    break;
                case "request_sync":
                    handleSyncRequest();
                    break;
                default:
                    plugin.getLogger().fine("[Redis] Unknown online players message type: " + type);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Redis] Error handling online players message: " + payload, e);
        }
    }

    private void handleJoin(JsonObject json) {
        String playerName = json.get("player").getAsString();
        String serverName = json.has("server") ? json.get("server").getAsString() : "unknown";
        RedisProxyManager proxyManager = getProxyManager();
        if (proxyManager != null) {
            proxyManager.addGlobalPlayer(playerName, serverName);
            plugin.getLogger().fine("[Redis] Player joined on server " + serverName + ": " + playerName);
        }
    }

    private void handleQuit(JsonObject json) {
        String playerName = json.get("player").getAsString();
        RedisProxyManager proxyManager = getProxyManager();
        if (proxyManager != null) {
            proxyManager.removeGlobalPlayer(playerName);
            plugin.getLogger().fine("[Redis] Player quit on another server: " + playerName);
        }
    }

    private void handleSync(JsonObject json) {
        JsonArray players = json.getAsJsonArray("players");
        String serverName = json.has("server") ? json.get("server").getAsString() : "unknown";
        RedisProxyManager proxyManager = getProxyManager();
        if (proxyManager != null) {
            // Clear players from this server before re-adding (in case some disconnected)
            proxyManager.clearPlayersFromServer(serverName);
            for (int i = 0; i < players.size(); i++) {
                proxyManager.addGlobalPlayer(players.get(i).getAsString(), serverName);
            }
            plugin.getLogger().fine("[Redis] Synced " + players.size() + " players from server " + serverName);
        }
    }

    private void handleSyncRequest() {
        // Another server is requesting our player list - send it
        if (plugin.getRedisManager() != null && plugin.getRedisManager().isInitialized()) {
            plugin.getRedisManager().publishLocalPlayersSync();
        }
    }

    private RedisProxyManager getProxyManager() {
        if (plugin.getProxyManager() instanceof RedisProxyManager) {
            return (RedisProxyManager) plugin.getProxyManager();
        }
        return null;
    }
}
