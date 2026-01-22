package net.sacredlabyrinth.phaed.simpleclans.listeners;

import net.sacredlabyrinth.phaed.simpleclans.*;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer.Channel;
import net.sacredlabyrinth.phaed.simpleclans.managers.PermissionsManager;
import net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager;
import net.sacredlabyrinth.phaed.simpleclans.utils.CurrencyFormat;
import net.sacredlabyrinth.phaed.simpleclans.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.*;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import static net.sacredlabyrinth.phaed.simpleclans.ClanPlayer.Channel.CLAN;
import static net.sacredlabyrinth.phaed.simpleclans.ClanPlayer.Channel.NONE;
import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage.Source.SPIGOT;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.*;

/**
 * @author phaed
 */
public class SCPlayerListener extends SCListener {

    private final SettingsManager settingsManager;

    public SCPlayerListener(@NotNull SimpleClans plugin) {
        super(plugin);
        settingsManager = plugin.getSettingsManager();
        registerChatListener();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String[] split = event.getMessage().substring(1).split(" ");
        String command = split[0];

        if (settingsManager.is(CLANCHAT_TAG_BASED)) {
            Clan clan = plugin.getClanManager().getClan(command);
            if (clan == null || !clan.isMember(event.getPlayer())) {
                return;
            }
            String replaced = event.getMessage().replaceFirst(command, settingsManager.getString(COMMANDS_CLAN_CHAT));
            event.setMessage(replaced);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void handleChatTags(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isBlacklistedWorld(player)) {
            return;
        }

        ClanPlayer cp = plugin.getClanManager().getAnyClanPlayer(player.getUniqueId());
        String tagLabel = cp != null && cp.isTagEnabled() ? cp.getTagLabel() : null;
        if (settingsManager.is(CHAT_COMPATIBILITY_MODE) && settingsManager.is(DISPLAY_CHAT_TAGS)) {
            if (tagLabel != null) {
                if (player.getDisplayName().contains("{clan}")) {
                    player.setDisplayName(player.getDisplayName().replace("{clan}", tagLabel));
                } else if (event.getFormat().contains("{clan}")) {
                    event.setFormat(event.getFormat().replace("{clan}", tagLabel));
                } else {
                    String format = event.getFormat();
                    event.setFormat(tagLabel + format);
                }
            } else {
                event.setFormat(event.getFormat().replace("{clan}", ""));
                event.setFormat(event.getFormat().replace("tagLabel", ""));
            }
        } else {
            plugin.getClanManager().updateDisplayName(player);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (isBlacklistedWorld(player)) {
            return;
        }
        ClanPlayer cp = plugin.getClanManager().getCreateClanPlayer(player.getUniqueId());

        updatePlayerName(player);
        plugin.getClanManager().updateLastSeen(player);
        plugin.getClanManager().updateDisplayName(player);

        plugin.getPermissionsManager().addPlayerPermissions(cp);

        Clan clan = cp.getClan();
        if (settingsManager.is(BB_SHOW_ON_LOGIN) && cp.isBbEnabled() && clan != null) {
            clan.displayBb(player, settingsManager.getInt(BB_LOGIN_SIZE));
            
            // Show fee info only for members who actually pay the fee
            // Excludes: leaders and members with FEE_BYPASS rank permission
            // Respects: global config (ECONOMY_MEMBER_FEE_ENABLED) + clan toggle (isMemberFeeEnabled)
            if (settingsManager.is(ECONOMY_MEMBER_FEE_ENABLED) 
                    && clan.isMemberFeeEnabled() 
                    && clan.getMemberFee() > 0
                    && clan.getFeePayers().contains(cp)) {
                int feeHour = settingsManager.getInt(TASKS_COLLECT_FEE_HOUR);
                int feeMinute = settingsManager.getInt(TASKS_COLLECT_FEE_MINUTE);
                String feeInfo = ChatUtils.parseColors(lang("fee.login.info", player,
                        CurrencyFormat.format(clan.getMemberFee()),
                        String.format("%02d", feeHour),
                        String.format("%02d", feeMinute)));
                player.sendMessage(feeInfo);
            }
            
            // Show upkeep info for leaders only
            // Respects: ECONOMY_UPKEEP_ENABLED + ECONOMY_UPKEEP_REQUIRES_MEMBER_FEE (if true, requires clan fee enabled)
            if (cp.isLeader() 
                    && settingsManager.is(ECONOMY_UPKEEP_ENABLED)
                    && settingsManager.getDouble(ECONOMY_UPKEEP) > 0) {
                // Check if upkeep requires member fee to be enabled
                boolean showUpkeep = true;
                if (settingsManager.is(ECONOMY_UPKEEP_REQUIRES_MEMBER_FEE) && !clan.isMemberFeeEnabled()) {
                    showUpkeep = false;
                }
                
                if (showUpkeep) {
                    int upkeepHour = settingsManager.getInt(TASKS_COLLECT_UPKEEP_HOUR);
                    int upkeepMinute = settingsManager.getInt(TASKS_COLLECT_UPKEEP_MINUTE);
                    String upkeepInfo = ChatUtils.parseColors(lang("upkeep.login.info", player,
                            CurrencyFormat.format(settingsManager.getDouble(ECONOMY_UPKEEP)),
                            String.format("%02d", upkeepHour),
                            String.format("%02d", upkeepMinute),
                            CurrencyFormat.format(clan.getBalance())));
                    player.sendMessage(upkeepInfo);
                }
            }
        }

        plugin.getPermissionsManager().addClanPermissions(cp);
        
        // Notify other servers via Redis
        if (plugin.getRedisManager() != null && plugin.getRedisManager().isInitialized()) {
            plugin.getRedisManager().publishPlayerJoin(player.getName());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!settingsManager.is(TELEPORT_HOME_ON_SPAWN) || isBlacklistedWorld(player)) {
            return;
        }

        Clan clan = plugin.getClanManager().getClanByPlayerUniqueId(player.getUniqueId());
        Location home;
        if (clan != null && (home = clan.getHomeLocation()) != null) {
            String homeServer = new Flags(clan.getFlags()).getString("homeServer", "");
            if (homeServer.isEmpty() || plugin.getProxyManager().getServerName().equals(homeServer)) {
                event.setRespawnLocation(plugin.getTeleportManager().getSafe(home));
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        ClanPlayer cp = plugin.getClanManager().getClanPlayer(event.getPlayer());
        if (cp != null) {
            Clan clan = Objects.requireNonNull(cp.getClan());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (clan.getOnlineMembers().isEmpty()) {
                    plugin.getProtectionManager().setWarExpirationTime(cp.getClan(),
                            settingsManager.getMinutes(WAR_DISCONNECT_EXPIRATION_TIME));
                }
            });
        }
        if (isBlacklistedWorld(event.getPlayer())) {
            return;
        }


        plugin.getPermissionsManager().removeClanPlayerPermissions(cp);
        plugin.getClanManager().updateLastSeen(event.getPlayer());
        plugin.getRequestManager().endPendingRequest(event.getPlayer().getName());
        
        // Notify other servers via Redis
        if (plugin.getRedisManager() != null && plugin.getRedisManager().isInitialized()) {
            plugin.getRedisManager().publishPlayerQuit(event.getPlayer().getName());
        }
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        if (isBlacklistedWorld(event.getPlayer())) {
            return;
        }

        plugin.getClanManager().updateLastSeen(event.getPlayer());
    }

    private void registerChatListener() {
        EventPriority priority = EventPriority.valueOf(settingsManager.getString(CLANCHAT_LISTENER_PRIORITY));
        plugin.getServer().getPluginManager().registerEvent(AsyncPlayerChatEvent.class, this, priority, (l, e) -> {
            if (!(e instanceof AsyncPlayerChatEvent)) {
                return;
            }
            AsyncPlayerChatEvent event = (AsyncPlayerChatEvent) e;
            Player player = event.getPlayer();
            ClanPlayer cp = plugin.getClanManager().getClanPlayer(player.getUniqueId());
            if (cp == null || isBlacklistedWorld(player)) {
                return;
            }

            Channel channel = cp.getChannel();
            if (channel != NONE) {
                PermissionsManager pm = plugin.getPermissionsManager();
                if ((channel == Channel.ALLY && !pm.has(player, "simpleclans.member.ally")) ||
                        (channel == CLAN && !pm.has(player, "simpleclans.member.chat"))) {
                    ChatBlock.sendMessage(player, ChatColor.RED + lang("insufficient.permissions", player));
                    return;
                }
                plugin.getChatManager().processChat(SPIGOT, channel, cp, event.getMessage());
                event.setCancelled(true);
            }
        }, plugin, true);
    }

    private void updatePlayerName(@NotNull final Player player) {
        final ClanPlayer cp = plugin.getClanManager().getAnyClanPlayer(player.getUniqueId());

        ClanPlayer duplicate = null;
        for (ClanPlayer other : plugin.getClanManager().getAllClanPlayers()) {
            if (other.getName().equals(player.getName()) && !other.getUniqueId().equals(player.getUniqueId())) {
                duplicate = other;
                break;
            }
        }

        if (duplicate != null) {
            plugin.getLogger().warning(String.format("Found duplicate for %s, UUIDs: %s, %s", player.getName(),
                    player.getUniqueId(), duplicate.getUniqueId()));
            duplicate.setName(duplicate.getUniqueId().toString());
            plugin.getStorageManager().updatePlayerName(duplicate);
        }
        if (cp != null) {
            cp.setName(player.getName());
            plugin.getStorageManager().updatePlayerName(cp);
        }
    }

}
