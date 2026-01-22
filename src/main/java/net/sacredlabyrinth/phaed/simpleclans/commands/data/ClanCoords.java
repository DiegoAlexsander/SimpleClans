package net.sacredlabyrinth.phaed.simpleclans.commands.data;

import net.sacredlabyrinth.phaed.simpleclans.*;
import net.sacredlabyrinth.phaed.simpleclans.proxy.RedisProxyManager;
import net.sacredlabyrinth.phaed.simpleclans.utils.VanishUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.*;
import static org.bukkit.ChatColor.*;

public class ClanCoords extends Sendable {

    private final Player player;
    private final Clan clan;

    public ClanCoords(@NotNull SimpleClans plugin, @NotNull Player player, @NotNull Clan clan) {
        super(plugin, player);
        this.player = player;
        this.clan = clan;
    }

    private void populateRows() {
        Map<Integer, List<String>> rows = new TreeMap<>();
        String localServer = getLocalServerId();
        
        for (ClanPlayer cpm : VanishUtils.getNonVanished(player, clan)) {
            Player p = cpm.toPlayer();

            if (p != null) {
                // Local player
                String name = (cpm.isLeader() ? sm.getColored(PAGE_LEADER_COLOR) : (cpm.isTrusted() ?
                        sm.getColored(PAGE_TRUSTED_COLOR) : sm.getColored(PAGE_UNTRUSTED_COLOR))) + cpm.getName();
                Location loc = p.getLocation();
                int distance = (int) Math.ceil(loc.toVector().distance(player.getLocation().toVector()));
                String coords = loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
                String world = loc.getWorld() == null ? "-" : loc.getWorld().getName();

                List<String> cols = new ArrayList<>();
                cols.add("  " + name);
                cols.add(AQUA + "" + distance);
                cols.add(WHITE + "" + coords);
                cols.add(world);
                cols.add(GRAY + localServer);
                rows.put(distance, cols);
            } else {
                // Remote player - check if online on another server
                String remoteServer = getRemotePlayerServer(cpm.getName());
                if (remoteServer != null) {
                    String name = (cpm.isLeader() ? sm.getColored(PAGE_LEADER_COLOR) : (cpm.isTrusted() ?
                            sm.getColored(PAGE_TRUSTED_COLOR) : sm.getColored(PAGE_UNTRUSTED_COLOR))) + cpm.getName();

                    List<String> cols = new ArrayList<>();
                    cols.add("  " + name);
                    cols.add(DARK_GRAY + "-");
                    cols.add(DARK_GRAY + "-");
                    cols.add(DARK_GRAY + "-");
                    cols.add(YELLOW + remoteServer);
                    // Use a high distance so remote players appear at the end
                    rows.put(Integer.MAX_VALUE - Math.abs(cpm.getName().hashCode()), cols);
                }
            }
        }
        for (List<String> col : rows.values()) {
            chatBlock.addRow(col.get(0), col.get(1), col.get(2), col.get(3), col.get(4));
        }
    }

    private void configureAndSendHeader() {
        chatBlock.setFlexibility(true, false, false, false, false);
        chatBlock.setAlignment("l", "c", "c", "c", "c");

        ChatBlock.sendBlank(player);
        ChatBlock.saySingle(player, sm.getColored(PAGE_CLAN_NAME_COLOR) + clan.getName() + subColor + " " +
                lang("coords", player) + " " + headColor + Helper.generatePageSeparator(sm.getString(PAGE_SEPARATOR)));
        ChatBlock.sendBlank(player);

        chatBlock.addRow("  " + headColor + lang("name", player), lang("distance", player),
                lang("coords.upper", player), lang("world", player), lang("server", player));
    }
    
    private String getLocalServerId() {
        if (plugin.getProxyManager() instanceof RedisProxyManager) {
            return ((RedisProxyManager) plugin.getProxyManager()).getLocalServerId();
        }
        return "local";
    }
    
    private String getRemotePlayerServer(String playerName) {
        if (plugin.getProxyManager() instanceof RedisProxyManager) {
            return ((RedisProxyManager) plugin.getProxyManager()).getPlayerServer(playerName);
        }
        return null;
    }

    @Override
    public void send() {
        configureAndSendHeader();
        populateRows();

        sendBlock();
    }
}
