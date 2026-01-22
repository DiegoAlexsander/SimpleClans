package net.sacredlabyrinth.phaed.simpleclans.tasks;

import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.Helper;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.managers.PermissionsManager;
import net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager;
import net.sacredlabyrinth.phaed.simpleclans.proxy.ProxyManager;
import net.sacredlabyrinth.phaed.simpleclans.utils.CurrencyFormat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.*;
import static org.bukkit.ChatColor.AQUA;
import static org.bukkit.ChatColor.RED;

/**
 * Task that warns members about upcoming fee collection.
 * Notifies all members about the fee, with a different message for those 
 * who don't have enough balance.
 *
 * @author DiegoAlexsander
 */
public class FeeWarningTask extends BukkitRunnable {
    private final SimpleClans plugin;
    private final SettingsManager sm;

    public FeeWarningTask() {
        plugin = SimpleClans.getInstance();
        sm = plugin.getSettingsManager();
    }

    /**
     * Starts the repetitive task
     */
    public void start() {
        int hour = sm.getInt(TASKS_COLLECT_FEE_WARNING_HOUR);
        int minute = sm.getInt(TASKS_COLLECT_FEE_WARNING_MINUTE);
        long delay = Helper.getDelayTo(hour, minute);

        this.runTaskTimer(plugin, delay * 20, 86400 * 20);
    }

    /**
     * Checks if this task should execute on this server.
     * In multi-server environments, only the designated server should run this task.
     */
    private boolean shouldExecuteOnThisServer() {
        String designatedServer = sm.getString(TASKS_COLLECT_FEE_SERVER);
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

        PermissionsManager pm = plugin.getPermissionsManager();
        ProxyManager proxyManager = plugin.getProxyManager();
        
        int collectionHour = sm.getInt(TASKS_COLLECT_FEE_HOUR);
        int collectionMinute = sm.getInt(TASKS_COLLECT_FEE_MINUTE);

        plugin.getClanManager().getClans().forEach((clan) -> {
            final double memberFee = clan.getMemberFee();
            if (!clan.isMemberFeeEnabled() || memberFee <= 0) {
                return;
            }

            for (ClanPlayer cp : clan.getFeePayers()) {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(cp.getUniqueId());
                double balance = pm.playerGetMoney(offlinePlayer);
                
                String warningMessage;
                if (balance < memberFee) {
                    // Not enough balance - urgent warning
                    warningMessage = RED + lang("fee.warning.not.enough.balance", cp, 
                            CurrencyFormat.format(memberFee), CurrencyFormat.format(balance));
                } else {
                    // Normal reminder with collection time
                    warningMessage = AQUA + lang("fee.warning.reminder", cp, 
                            CurrencyFormat.format(memberFee),
                            String.format("%02d", collectionHour),
                            String.format("%02d", collectionMinute));
                }

                // Send to player if online (local or cross-server)
                Player localPlayer = cp.toPlayer();
                if (localPlayer != null) {
                    localPlayer.sendMessage(warningMessage);
                } else if (proxyManager != null && proxyManager.isOnline(cp.getName())) {
                    proxyManager.sendMessage(cp.getName(), warningMessage);
                }
            }
        });
    }
}
