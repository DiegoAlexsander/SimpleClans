package net.sacredlabyrinth.phaed.simpleclans.tasks;

import net.sacredlabyrinth.phaed.simpleclans.*;
import net.sacredlabyrinth.phaed.simpleclans.loggers.BankOperator;
import net.sacredlabyrinth.phaed.simpleclans.managers.PermissionsManager;
import net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager;
import net.sacredlabyrinth.phaed.simpleclans.proxy.ProxyManager;
import net.sacredlabyrinth.phaed.simpleclans.utils.CurrencyFormat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitRunnable;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static net.sacredlabyrinth.phaed.simpleclans.events.ClanBalanceUpdateEvent.Cause;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.*;
import static org.bukkit.ChatColor.AQUA;

/**
 *
 * @author roinujnosde
 */
public class CollectFeeTask extends BukkitRunnable {
	private final SimpleClans plugin;
    
	public CollectFeeTask() {
		plugin = SimpleClans.getInstance();
	}
	
    /**
     * Starts the repetitive task
     */
    public void start() {
    	SettingsManager sm = plugin.getSettingsManager();
    	
    	int hour = sm.getInt(TASKS_COLLECT_FEE_HOUR);
    	int minute = sm.getInt(TASKS_COLLECT_FEE_MINUTE);
        long delay = Helper.getDelayTo(hour, minute);
        
        this.runTaskTimer(plugin, delay * 20, 86400 * 20);
    }
    
    /**
     * Checks if this server should execute the fee collection.
     * In multi-server environments, only the designated server should collect fees.
     *
     * @return true if this server should collect fees
     */
    private boolean shouldExecuteOnThisServer() {
        SettingsManager sm = plugin.getSettingsManager();
        String designatedServer = sm.getString(TASKS_COLLECT_FEE_SERVER);
        
        // If no server is designated, execute on all servers (backwards compatibility)
        if (designatedServer == null || designatedServer.isEmpty()) {
            return true;
        }
        
        // Check if we have a proxy manager with server ID
        ProxyManager proxyManager = plugin.getProxyManager();
        if (proxyManager == null) {
            // No proxy manager, execute on all servers
            return true;
        }
        
        String currentServerId = proxyManager.getServerName();
        return designatedServer.equalsIgnoreCase(currentServerId);
    }
    
    /**
     * (used internally)
     */
    @Override
    public void run() {
        if (!shouldExecuteOnThisServer()) {
            return;
        }
        
        PermissionsManager pm = plugin.getPermissionsManager();
        
        for (Clan clan : plugin.getClanManager().getClans()) {
            final double memberFee = clan.getMemberFee();
            if (!clan.isMemberFeeEnabled() || memberFee <= 0) {
                continue;
            }

            for (ClanPlayer cp : clan.getFeePayers()) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(cp.getUniqueId());
                
                boolean success = pm.chargePlayer(player, memberFee);
                double balanceAfter = pm.playerGetMoney(player);
                
                if (success) {
                    // Send message via proxy if player might be on another server
                    String feeMessage = AQUA + lang("fee.collected", cp, CurrencyFormat.format(memberFee));
                    if (player.isOnline()) {
                        // Player is on this server, send directly
                        ChatBlock.sendMessage(cp, feeMessage);
                    } else if (plugin.getProxyManager() != null && plugin.getProxyManager().isOnline(cp.getName())) {
                        // Player is online on another server, send via proxy
                        plugin.getProxyManager().sendMessage(cp.getName(), feeMessage);
                    }

                    clan.deposit(new BankOperator(cp, balanceAfter), Cause.MEMBER_FEE, memberFee);
                    plugin.getStorageManager().updateClan(clan);
                } else {
                    clan.removePlayerFromClan(cp.getUniqueId());
                    clan.addBb(lang("bb.fee.player.kicked", cp.getName()));
                }
            }
        }
    }
}
