package net.sacredlabyrinth.phaed.simpleclans.tasks;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.EconomyResponse;
import net.sacredlabyrinth.phaed.simpleclans.Helper;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.events.ClanBalanceUpdateEvent;
import net.sacredlabyrinth.phaed.simpleclans.loggers.BankOperator;
import net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager;
import net.sacredlabyrinth.phaed.simpleclans.proxy.ProxyManager;
import net.sacredlabyrinth.phaed.simpleclans.utils.CurrencyFormat;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.text.MessageFormat;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.*;
import static org.bukkit.ChatColor.AQUA;

/**
 *
 * @author roinujnosde
 */
public class CollectUpkeepTask extends BukkitRunnable {
	private final SimpleClans plugin;
	private final SettingsManager settingsManager;
	
	public CollectUpkeepTask() {
		plugin = SimpleClans.getInstance();
		settingsManager = plugin.getSettingsManager();
	}

    /**
     * Starts the repetitive task
     */
    public void start() {
    	int hour = settingsManager.getInt(TASKS_COLLECT_UPKEEP_HOUR);
    	int minute = settingsManager.getInt(TASKS_COLLECT_UPKEEP_MINUTE);
        long delay = Helper.getDelayTo(hour, minute);

        this.runTaskTimer(plugin, delay * 20, 86400 * 20);
    }
    
    /**
     * Checks if this server should execute the upkeep collection.
     * In multi-server environments, only the designated server should collect upkeep.
     *
     * @return true if this server should collect upkeep
     */
    private boolean shouldExecuteOnThisServer() {
        String designatedServer = settingsManager.getString(TASKS_COLLECT_UPKEEP_SERVER);
        
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
    	if (plugin == null) {
    		throw new IllegalStateException("Use the start() method!");
    	}
    	
    	if (!shouldExecuteOnThisServer()) {
            return;
        }
        
    	plugin.getClanManager().getClans().forEach((clan) -> {
        	if (settingsManager.is(ECONOMY_UPKEEP_REQUIRES_MEMBER_FEE) && !clan.isMemberFeeEnabled()) {
        		return;
        	}
            double upkeep = settingsManager.getDouble(ECONOMY_UPKEEP);
            if (settingsManager.is(ECONOMY_MULTIPLY_UPKEEP_BY_CLAN_SIZE)) {
                upkeep = upkeep * clan.getSize();
            }

            EconomyResponse response = clan.withdraw(BankOperator.INTERNAL, ClanBalanceUpdateEvent.Cause.UPKEEP, upkeep);
            if (response == EconomyResponse.NOT_ENOUGH_BALANCE) {
                clan.disband(null, true, false);
            }
            if (response == EconomyResponse.SUCCESS) {
                clan.addBb(MessageFormat.format(lang("upkeep.collected"), CurrencyFormat.format(upkeep)), false);
                
                // Send notification to online members (local and cross-server)
                String notificationMessage = AQUA + MessageFormat.format(lang("upkeep.collected.notification"), 
                        CurrencyFormat.format(upkeep), CurrencyFormat.format(clan.getBalance()));
                sendUpkeepNotification(clan, notificationMessage);
            }
        });
    }
    
    /**
     * Sends upkeep collection notification to all online members (local and cross-server)
     */
    private void sendUpkeepNotification(Clan clan, String message) {
        ProxyManager proxyManager = plugin.getProxyManager();
        
        for (ClanPlayer cp : clan.getMembers()) {
            Player localPlayer = cp.toPlayer();
            if (localPlayer != null) {
                // Member is on this server
                localPlayer.sendMessage(message);
            } else if (proxyManager != null && proxyManager.isOnline(cp.getName())) {
                // Member is on another server
                proxyManager.sendMessage(cp.getName(), message);
            }
        }
    }

}
