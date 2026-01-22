package net.sacredlabyrinth.phaed.simpleclans.tasks;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.Helper;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager;
import net.sacredlabyrinth.phaed.simpleclans.proxy.ProxyManager;
import net.sacredlabyrinth.phaed.simpleclans.utils.CurrencyFormat;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.*;
import static org.bukkit.ChatColor.AQUA;
import static org.bukkit.ChatColor.RED;

/**
 * Task that warns leaders about upcoming upkeep collection.
 * Notifies all leaders about the upkeep, with a different message 
 * if the clan doesn't have enough balance.
 *
 * @author roinujnosde
 * @author DiegoAlexsander
 */
public class UpkeepWarningTask extends BukkitRunnable {
    private final SimpleClans plugin;
    private final SettingsManager sm;

    public UpkeepWarningTask() {
        plugin = SimpleClans.getInstance();
        sm = plugin.getSettingsManager();
    }
    
    /**
     * Checks if this task should execute on this server.
     * In multi-server environments, only the designated server should run this task.
     */
    private boolean shouldExecuteOnThisServer() {
        String designatedServer = sm.getString(TASKS_COLLECT_UPKEEP_SERVER);
        if (designatedServer == null || designatedServer.isEmpty()) {
            return true; // No server specified, run on all servers
        }
        
        // Check if this server is the designated one
        if (plugin.getProxyManager() != null) {
            String thisServer = plugin.getProxyManager().getServerName();
            return designatedServer.equalsIgnoreCase(thisServer);
        }
        
        return true; // No proxy manager, run anyway
    }

    /**
     * Starts the repetitive task
     */
    public void start() {
        int hour = sm.getInt(TASKS_COLLECT_UPKEEP_WARNING_HOUR);
        int minute = sm.getInt(TASKS_COLLECT_UPKEEP_WARNING_MINUTE);
        long delay = Helper.getDelayTo(hour, minute);
        
        this.runTaskTimer(plugin, delay * 20, 86400 * 20);
    }

    /**
     * (used internally)
     */
    @Override
    public void run() {
        if (plugin == null) {
            throw new IllegalStateException("Use the start() method!");
        }
        
        if (!shouldExecuteOnThisServer()) {
            return;
        }
        
        int collectionHour = sm.getInt(TASKS_COLLECT_UPKEEP_HOUR);
        int collectionMinute = sm.getInt(TASKS_COLLECT_UPKEEP_MINUTE);
        
        plugin.getClanManager().getClans().forEach((clan) -> {
            if (sm.is(ECONOMY_UPKEEP_REQUIRES_MEMBER_FEE) && !clan.isMemberFeeEnabled()) {
                return;
            }
            
            final double balance = clan.getBalance();
            double upkeep = sm.getDouble(ECONOMY_UPKEEP);
            if (sm.is(ECONOMY_MULTIPLY_UPKEEP_BY_CLAN_SIZE)) {
                upkeep = upkeep * clan.getSize();
            }
            
            final double finalUpkeep = upkeep;
            String warningMessage;
            
            if (balance < upkeep) {
                // Not enough balance - urgent warning (red)
                warningMessage = RED + lang("upkeep.warning.not.enough.balance",
                        CurrencyFormat.format(finalUpkeep), 
                        CurrencyFormat.format(balance),
                        String.format("%02d", collectionHour),
                        String.format("%02d", collectionMinute));
                // Also add to bulletin board when critical
                clan.addBb(warningMessage, false);
            } else {
                // Normal reminder (aqua) - without showing current balance
                warningMessage = AQUA + lang("upkeep.warning.reminder",
                        CurrencyFormat.format(finalUpkeep),
                        String.format("%02d", collectionHour),
                        String.format("%02d", collectionMinute));
            }
            
            // Send to online leaders (local and cross-server)
            sendWarningToLeaders(clan, warningMessage);
        });
    }
    
    /**
     * Sends warning message to all online leaders (local and cross-server)
     */
    private void sendWarningToLeaders(Clan clan, String message) {
        ProxyManager proxyManager = plugin.getProxyManager();
        
        for (ClanPlayer leader : clan.getLeaders()) {
            Player localPlayer = leader.toPlayer();
            if (localPlayer != null) {
                // Leader is on this server
                localPlayer.sendMessage(message);
            } else if (proxyManager != null && proxyManager.isOnline(leader.getName())) {
                // Leader is on another server
                proxyManager.sendMessage(leader.getName(), message);
            }
        }
    }
}
