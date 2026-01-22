package net.sacredlabyrinth.phaed.simpleclans.commands.completions;

import co.aikar.commands.BukkitCommandCompletionContext;
import co.aikar.commands.InvalidCommandArgument;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.proxy.RedisProxyManager;
import net.sacredlabyrinth.phaed.simpleclans.utils.VanishUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

/**
 * Completion for all online players across all servers.
 * This overrides the default ACF @players completion to include
 * players from other servers via Redis.
 */
@SuppressWarnings("unused")
public class PlayersCompletion extends AbstractSyncCompletion {
    
    public PlayersCompletion(@NotNull SimpleClans plugin) {
        super(plugin);
    }

    @Override
    public @NotNull String getId() {
        return "players";
    }

    @Override
    public Collection<String> getCompletions(BukkitCommandCompletionContext c) throws InvalidCommandArgument {
        Collection<String> onlinePlayers = new ArrayList<>();

        // Add local online players
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            boolean vanished = VanishUtils.isVanished(c.getSender(), onlinePlayer);
            if (c.hasConfig("ignore_vanished") && vanished) {
                continue;
            }
            onlinePlayers.add(onlinePlayer.getName());
        }
        
        // Add players from other servers (via Redis)
        if (plugin.getProxyManager() instanceof RedisProxyManager) {
            RedisProxyManager redisProxy = (RedisProxyManager) plugin.getProxyManager();
            Set<String> globalPlayers = redisProxy.getGlobalOnlinePlayers();
            for (String playerName : globalPlayers) {
                // Name already has correct capitalization from Redis
                if (!containsIgnoreCase(onlinePlayers, playerName)) {
                    onlinePlayers.add(playerName);
                }
            }
        }
        
        return onlinePlayers;
    }
    
    private boolean containsIgnoreCase(Collection<String> collection, String name) {
        for (String s : collection) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
