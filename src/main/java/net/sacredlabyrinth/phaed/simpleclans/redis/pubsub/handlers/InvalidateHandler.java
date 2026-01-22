package net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.MessageHandler;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Handles cache invalidation messages from other servers.
 * 
 * <p>Message format: "type:id"</p>
 * <ul>
 *   <li>clan:TAG - Invalidate clan cache</li>
 *   <li>clan:delete:TAG - Remove clan from cache</li>
 *   <li>player:UUID - Invalidate player cache</li>
 *   <li>player:delete:UUID - Remove player from cache</li>
 *   <li>all - Invalidate all caches</li>
 * </ul>
 */
public class InvalidateHandler implements MessageHandler {

    private final SimpleClans plugin;

    public InvalidateHandler(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(@NotNull String payload) {
        try {
            String[] parts = payload.split(":", 3);
            if (parts.length < 1) {
                return;
            }

            String type = parts[0];

            switch (type) {
                case "clan":
                    handleClanInvalidation(parts);
                    break;
                case "player":
                    handlePlayerInvalidation(parts);
                    break;
                case "all":
                    handleFullInvalidation();
                    break;
                default:
                    plugin.getLogger().fine("[Redis] Unknown invalidation type: " + type);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Redis] Error handling invalidation: " + payload, e);
        }
    }

    private void handleClanInvalidation(String[] parts) {
        if (parts.length < 2) {
            return;
        }

        if (parts.length >= 3 && "delete".equals(parts[1])) {
            // clan:delete:TAG
            String tag = parts[2];
            plugin.getClanManager().removeClan(tag);
            plugin.getLogger().fine("[Redis] Removed clan from cache: " + tag);
        } else if (parts.length >= 3 && "new".equals(parts[1])) {
            // clan:new:TAG - new clan created on another server
            String tag = parts[2];
            plugin.getClanManager().invalidateLocalClanCache(tag);
        } else {
            // clan:TAG
            String tag = parts[1];
            plugin.getClanManager().invalidateLocalClanCache(tag);
        }
    }

    private void handlePlayerInvalidation(String[] parts) {
        if (parts.length < 2) {
            return;
        }

        try {
            if (parts.length >= 3 && "delete".equals(parts[1])) {
                // player:delete:UUID
                UUID uuid = UUID.fromString(parts[2]);
                plugin.getClanManager().deleteClanPlayerFromMemory(uuid);
                plugin.getLogger().fine("[Redis] Removed player from cache: " + uuid);
            } else {
                // player:UUID
                UUID uuid = UUID.fromString(parts[1]);
                plugin.getClanManager().invalidateLocalPlayerCache(uuid);
                plugin.getLogger().fine("[Redis] Invalidated player cache: " + uuid);
            }
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[Redis] Invalid UUID in invalidation message: " + parts[1]);
        }
    }

    private void handleFullInvalidation() {
        plugin.getClanManager().invalidateAllLocalCaches();
        plugin.getLogger().info("[Redis] Full cache invalidation received");
    }
}
