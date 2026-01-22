package net.sacredlabyrinth.phaed.simpleclans.conversation;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.redis.RedisManager;
import net.sacredlabyrinth.phaed.simpleclans.redis.lock.DistributedLock;
import org.bukkit.conversations.Prompt;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static org.bukkit.ChatColor.RED;

public class DisbandPrompt extends ConfirmationPrompt {

    @Override
    protected Prompt confirm(ClanPlayer sender, Clan clan) {
        if (clan.isPermanent()) {
            return new MessagePromptImpl(RED + lang("cannot.disband.permanent", sender));
        }

        // Try to acquire distributed lock for disband operation
        RedisManager redis = SimpleClans.getInstance().getRedisManager();
        if (redis != null && redis.isInitialized()) {
            try (DistributedLock lock = redis.acquireDisbandLock(clan.getTag())) {
                if (!lock.tryAcquire()) {
                    return new MessagePromptImpl(RED + lang("disband.operation.in.progress", sender));
                }
                clan.disband(sender.toPlayer(), true, false);
            }
        } else {
            // No Redis, proceed without lock
            clan.disband(sender.toPlayer(), true, false);
        }
        return new MessagePromptImpl(RED + lang("clan.has.been.disbanded", sender, clan.getName()));
    }

    @Override
    protected String getPromptTextKey() {
        return "disband.confirmation";
    }

    @Override
    protected String getDeclineTextKey() {
        return "disband.request.cancelled";
    }

}
