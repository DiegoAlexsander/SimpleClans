package net.sacredlabyrinth.phaed.simpleclans.managers;

import net.sacredlabyrinth.phaed.simpleclans.*;
import net.sacredlabyrinth.phaed.simpleclans.events.RequestEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.RequestFinishedEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.WarEndEvent;
import net.sacredlabyrinth.phaed.simpleclans.redis.RedisManager;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers.RequestHandler;
import net.sacredlabyrinth.phaed.simpleclans.redis.request.RedisRequestStorage;
import net.sacredlabyrinth.phaed.simpleclans.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.*;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.*;
import static org.bukkit.ChatColor.RED;

/**
 * @author phaed
 */
public final class RequestManager {
    private final SimpleClans plugin;
    private final HashMap<String, Request> requests = new HashMap<>();

    /**
     *
     */
    public RequestManager() {
        plugin = SimpleClans.getInstance();
        askerTask();
    }

    /**
     * Checks if Redis is available for request storage.
     *
     * @return true if Redis is initialized
     */
    private boolean isRedisEnabled() {
        RedisManager redisManager = plugin.getRedisManager();
        return redisManager != null && redisManager.isInitialized();
    }

    /**
     * Gets the Redis request storage if available.
     *
     * @return the storage, or null if Redis is disabled
     */
    @Nullable
    private RedisRequestStorage getRedisStorage() {
        RedisManager redisManager = plugin.getRedisManager();
        if (redisManager != null && redisManager.isInitialized()) {
            return redisManager.getRequestStorage();
        }
        return null;
    }

    /**
     * Publishes a request notification to other servers via Redis.
     *
     * @param key     the request key
     * @param request the request
     */
    private void publishNewRequest(@NotNull String key, @NotNull Request request) {
        RedisManager redisManager = plugin.getRedisManager();
        if (redisManager != null && redisManager.isInitialized()) {
            // For INVITE, DEMOTE, and PROMOTE, the target is a player name
            String targetPlayer = null;
            if (request.getType() == ClanRequest.INVITE || 
                request.getType() == ClanRequest.DEMOTE || 
                request.getType() == ClanRequest.PROMOTE) {
                targetPlayer = request.getTarget();
            }
            // For inter-clan requests, the target is the clan tag that should receive notifications
            String targetClanTag = isInterClanRequest(request.getType()) ? request.getTarget() : null;
            String message = RequestHandler.createNewRequestMessage(
                    key,
                    request.getType(),
                    request.getClan() != null ? request.getClan().getTag() : null,
                    targetClanTag,
                    targetPlayer,
                    request.getMsg()
            );
            redisManager.publish(RedisManager.CHANNEL_REQUEST, message);
        }
    }
    
    /**
     * Checks if the request type is an inter-clan request (involves two clans).
     */
    private boolean isInterClanRequest(ClanRequest type) {
        return type == ClanRequest.CREATE_ALLY ||
               type == ClanRequest.BREAK_RIVALRY ||
               type == ClanRequest.START_WAR ||
               type == ClanRequest.END_WAR;
    }

    /**
     * Publishes a vote notification to other servers via Redis.
     *
     * @param key       the request key
     * @param voterName the voter's name
     * @param vote      the vote result
     */
    private void publishVote(@NotNull String key, @NotNull String voterName, @NotNull VoteResult vote) {
        RedisManager redisManager = plugin.getRedisManager();
        if (redisManager != null && redisManager.isInitialized()) {
            String message = RequestHandler.createVoteMessage(key, voterName, vote);
            redisManager.publish(RedisManager.CHANNEL_REQUEST, message);
        }
    }

    /**
     * Publishes a request removal to other servers via Redis.
     *
     * @param key    the request key
     * @param reason the removal reason
     */
    private void publishRemoval(@NotNull String key, @NotNull String reason) {
        RedisManager redisManager = plugin.getRedisManager();
        if (redisManager != null && redisManager.isInitialized()) {
            String message = RequestHandler.createRemoveMessage(key, reason);
            redisManager.publish(RedisManager.CHANNEL_REQUEST, message);
        }
    }

    /**
     * Stores a request in Redis if enabled.
     *
     * @param key     the request key
     * @param request the request
     */
    private void storeInRedis(@NotNull String key, @NotNull Request request) {
        RedisRequestStorage storage = getRedisStorage();
        if (storage != null) {
            storage.storeRequest(key, request);
            publishNewRequest(key, request);
        }
    }

    /**
     * Updates a request in Redis if enabled (e.g., after a vote).
     *
     * @param key     the request key
     * @param request the request with updated votes
     */
    private void updateRequestInRedis(@NotNull String key, @NotNull Request request) {
        RedisRequestStorage storage = getRedisStorage();
        if (storage != null) {
            storage.updateRequest(key, request);
        }
    }

    /**
     * Removes a request from Redis if enabled.
     *
     * @param key    the request key
     * @param reason the removal reason
     */
    private void removeFromRedis(@NotNull String key, @NotNull String reason) {
        RedisRequestStorage storage = getRedisStorage();
        if (storage != null) {
            storage.removeRequest(key);
            publishRemoval(key, reason);
        }
    }

    /**
     * Gets a request from local memory or Redis.
     *
     * @param key the request key
     * @return the request, or null if not found
     */
    @Nullable
    private Request getRequest(@NotNull String key) {
        // Check local first
        Request req = requests.get(key);
        if (req != null) {
            return req;
        }
        // Check Redis if enabled
        RedisRequestStorage storage = getRedisStorage();
        if (storage != null) {
            return storage.getRequest(key);
        }
        return null;
    }

    /**
     * Fetches the latest request state from Redis, updating the local cache.
     * This is used to synchronize vote states between servers.
     *
     * @param key the request key
     * @return the request from Redis, or null if not found
     */
    @Nullable
    private Request fetchRequestFromRedis(@NotNull String key) {
        RedisRequestStorage storage = getRedisStorage();
        if (storage != null) {
            Request req = storage.getRequest(key);
            if (req != null) {
                // Update local cache with Redis state
                requests.put(key, req);
            }
            return req;
        }
        return null;
    }

    /**
     * Applies a vote received from another server via Redis.
     * This updates the local request state and processes results if voting is finished.
     *
     * @param requestKey the request key
     * @param voterName  the voter's name
     * @param vote       the vote result
     */
    public void applyRemoteVote(@NotNull String requestKey, @NotNull String voterName, @NotNull VoteResult vote) {
        // Fetch the latest state from Redis (which includes the remote vote)
        Request req = fetchRequestFromRedis(requestKey);
        
        if (req == null) {
            // Try local request as fallback
            req = requests.get(requestKey);
            if (req != null) {
                // Apply vote to local request
                req.vote(voterName, vote);
            } else {
                return;
            }
        }
        
        // NOTE: We do NOT call processResults() here because the server where the vote
        // was cast already processed the results. We only update local state.
        // The request will be removed when we receive the removal notification.
    }

    /**
     * Removes a request from local memory only.
     * Called when receiving a remove notification from another server.
     *
     * @param key the request key
     */
    public void removeLocalRequest(@NotNull String key) {
        requests.remove(key.toLowerCase());
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean hasRequest(String tag) {
        // Check local first
        if (requests.containsKey(tag)) {
            return true;
        }
        // Check Redis if enabled
        RedisRequestStorage storage = getRedisStorage();
        if (storage != null) {
            return storage.hasRequest(tag);
        }
        return false;
    }

    public void addDemoteRequest(ClanPlayer requester, String demotedName, Clan clan) {
        if (hasRequest(clan.getTag())) {
            return;
        }
        String msg = MessageFormat.format(lang("asking.for.the.demotion"), requester.getName(), demotedName);

        ClanPlayer demotedTp = plugin.getClanManager().getAnyClanPlayer(demotedName);

        List<ClanPlayer> acceptors = Helper.stripOffLinePlayersGlobal(clan.getLeaders());
        acceptors.remove(demotedTp);

        Request req = new Request(ClanRequest.DEMOTE, acceptors, requester, demotedName, clan, msg);
        req.vote(requester.getName(), VoteResult.ACCEPT);
        String key = req.getClan().getTag();
        requests.put(key, req);
        storeInRedis(key, req);
        ask(req);
    }

    /**
     * This method asks <i>all</i> leaders about
     * some action <i>inside</i> their clan.
     * <p>
     * Example of possible requests:
     * </p>
     * <ul>
     *     <li>Disband request can be asked from all leaders</li>
     *     <li>Rename request can be asked from all leaders</li>
     *     <li>Promote request can be asked from all leaders</li>
     * </ul>
     *
     * <p>
     * Examples of incompatible requests:
     * </p>
     * <ul>
     *      <li>Demote request can be asked from all leaders,
     *      <b>except the demoted one.</b></li>
     *      <li>Invite request has to ask someone <b>outside</b> of leaders clan</li>
     * </ul>
     *
     * @param requester the clan player, who sent the request
     * @param request   the type of request, see: {@link ClanRequest}
     * @param target    the target which will be used in request processing
     * @param langKey   the language key that would be translated and send the message to all leaders
     * @param args      the language objects, requires in some language strings.
     * @throws IllegalArgumentException if passed incompatible request
     */
    public void requestAllLeaders(@NotNull ClanPlayer requester, @NotNull ClanRequest request,
                                  @NotNull String target, @NotNull String langKey, @Nullable Object... args) {
        if (request.equals(ClanRequest.INVITE) || request.equals(ClanRequest.DEMOTE)) {
            throw new IllegalArgumentException("Unsupported request: " + request.name());
        }

        Clan clan = requester.getClan();
        if (clan == null || hasRequest(clan.getTag())) {
            return;
        }

        String msg = lang(langKey, args);
        List<ClanPlayer> acceptors = Helper.stripOffLinePlayersGlobal(clan.getLeaders());

        Request req = new Request(request, acceptors, requester, target, clan, msg);
        String key = clan.getTag();
        requests.put(key, req);
        req.vote(requester.getName(), VoteResult.ACCEPT);
        storeInRedis(key, req);

        ask(req);
    }

    /**
     * Add a member invite request
     *
     * @param requester   the requester
     * @param invitedName the invited Player
     * @param clan        the Clan
     */
    public void addInviteRequest(ClanPlayer requester, String invitedName, Clan clan) {
        if (hasRequest(invitedName.toLowerCase())) {
            return;
        }
        
        // Check if player is online locally
        Player localPlayer = Bukkit.getPlayer(invitedName);
        
        // Check if player is online on another server (via Redis)
        final boolean isRemotePlayer;
        if (localPlayer == null && isRedisEnabled()) {
            isRemotePlayer = plugin.getProxyManager().isOnline(invitedName);
        } else {
            isRemotePlayer = false;
        }
        
        // If player is neither local nor remote, abort
        if (localPlayer == null && !isRemotePlayer) {
            return;
        }

        // For remote players, we need to get/create a locale context
        // Use the requester's locale for the message since we don't have the target's locale
        String msg = lang("inviting.you.to.join", requester.toPlayer(), requester.getName(), clan.getName());
        Request req = new Request(ClanRequest.INVITE, null, requester, invitedName, clan, msg);
        String key = invitedName.toLowerCase();
        requests.put(key, req);
        storeInRedis(key, req);
        ask(req);
    }

    public void addWarStartRequest(ClanPlayer requester, Clan warClan, Clan requestingClan) {
        if (hasRequest(warClan.getTag())) {
            return;
        }
        String msg = MessageFormat.format(lang("proposing.war"), requestingClan.getName(), ChatUtils.stripColors(warClan.getColorTag()));

        List<ClanPlayer> acceptors = Helper.stripOffLinePlayersGlobal(warClan.getLeaders());
        acceptors.remove(requester);

        Request req = new Request(ClanRequest.START_WAR, acceptors, requester, warClan.getTag(), requestingClan, msg);
        String key = req.getTarget();
        requests.put(key, req);
        storeInRedis(key, req);
        ask(req);
    }

    public void addWarEndRequest(ClanPlayer requester, Clan warClan, Clan requestingClan) {
        if (hasRequest(warClan.getTag())) {
            return;
        }
        String msg = MessageFormat.format(lang("proposing.to.end.the.war"), requestingClan.getName(), ChatUtils.stripColors(warClan.getColorTag()));

        List<ClanPlayer> acceptors = Helper.stripOffLinePlayersGlobal(warClan.getLeaders());
        acceptors.remove(requester);

        Request req = new Request(ClanRequest.END_WAR, acceptors, requester, warClan.getTag(), requestingClan, msg);
        String key = req.getTarget();
        requests.put(key, req);
        storeInRedis(key, req);
        ask(req);
    }

    public void addAllyRequest(ClanPlayer requester, Clan allyClan, Clan requestingClan) {
        if (hasRequest(allyClan.getTag())) {
            return;
        }
        String msg = MessageFormat.format(lang("proposing.an.alliance"), requestingClan.getName(), ChatUtils.stripColors(allyClan.getColorTag()));

        List<ClanPlayer> acceptors = Helper.stripOffLinePlayersGlobal(allyClan.getLeaders());
        acceptors.remove(requester);

        Request req = new Request(ClanRequest.CREATE_ALLY, acceptors, requester, allyClan.getTag(), requestingClan, msg);
        String key = req.getTarget();
        requests.put(key, req);
        storeInRedis(key, req);
        ask(req);
    }

    public void addRivalryBreakRequest(ClanPlayer requester, Clan rivalClan, Clan requestingClan) {
        if (hasRequest(rivalClan.getTag())) {
            return;
        }
        String msg = MessageFormat.format(lang("proposing.to.end.the.rivalry"), requestingClan.getName(), ChatUtils.stripColors(rivalClan.getColorTag()));

        List<ClanPlayer> acceptors = Helper.stripOffLinePlayersGlobal(rivalClan.getLeaders());
        acceptors.remove(requester);

        Request req = new Request(ClanRequest.BREAK_RIVALRY, acceptors, requester, rivalClan.getTag(), requestingClan, msg);
        String key = req.getTarget();
        requests.put(key, req);
        storeInRedis(key, req);
        ask(req);
    }

    public void accept(ClanPlayer cp) {
        // First check for clan-level requests (leader votes)
        final String clanTag = cp.getTag();
        Request req = getRequest(clanTag);

        if (req != null) {
            req.vote(cp.getName(), VoteResult.ACCEPT);
            
            // Update request in Redis with the new vote
            updateRequestInRedis(clanTag, req);
            
            // Publish vote to other servers
            publishVote(clanTag, cp.getName(), VoteResult.ACCEPT);
            
            processResults(req);
        } else {
            // Check for personal requests (invites) - use player name as key
            Request inviteReq = getRequest(cp.getCleanName());

            if (inviteReq != null) {
                processInvite(inviteReq, VoteResult.ACCEPT);
            }
        }
    }

    public void deny(ClanPlayer cp) {
        // First check for clan-level requests (leader votes)
        final String clanTag = cp.getTag();
        Request req = getRequest(clanTag);

        if (req != null) {
            req.vote(cp.getName(), VoteResult.DENY);
            
            // Update request in Redis with the new vote
            updateRequestInRedis(clanTag, req);
            
            // Publish vote to other servers
            publishVote(clanTag, cp.getName(), VoteResult.DENY);
            
            processResults(req);
        } else {
            // Check for personal requests (invites) - use player name as key
            Request inviteReq = getRequest(cp.getCleanName());

            if (inviteReq != null) {
                processInvite(inviteReq, VoteResult.DENY);
            }
        }
    }

    public void processInvite(Request req, VoteResult vote) {
        String key = req.getTarget().toLowerCase();
        requests.remove(key);
        removeFromRedis(key, "invite_" + vote.name().toLowerCase());

        Clan clan = req.getClan();
        Player invited = Bukkit.getPlayerExact(req.getTarget());
        if (invited == null) {
            return;
        }

        if (vote.equals(VoteResult.ACCEPT)) {
            ClanPlayer cp = plugin.getClanManager().getCreateClanPlayer(invited.getUniqueId());
            int maxMembers = !clan.isVerified() ? plugin.getSettingsManager().getInt(CLAN_UNVERIFIED_MAX_MEMBERS) : plugin.getSettingsManager().getInt(CLAN_MAX_MEMBERS);

            if (maxMembers > 0 && maxMembers > clan.getSize()) {
                ChatBlock.sendMessageKey(invited, "accepted.invitation", clan.getName());
                clan.addBb(lang("joined.the.clan", invited.getName()));
                plugin.getClanManager().serverAnnounce(lang("has.joined", invited.getName(), clan.getName()));
                clan.addPlayerToClan(cp);
            } else {
                ChatBlock.sendMessageKey(invited, "this.clan.has.reached.the.member.limit");
            }
        } else {
            ChatBlock.sendMessageKey(invited, "denied.invitation", clan.getName());
            clan.leaderAnnounce(RED + lang("membership.invitation", invited.getName()));
        }
    }

    public void processResults(Request req) {
        Clan requestClan = req.getClan();
        ClanPlayer requester = req.getRequester();

        String target = req.getTarget();

        @Nullable
        Clan targetClan = plugin.getClanManager().getClan(target);

        ClanPlayer targetCp = plugin.getClanManager().getAnyClanPlayer(target);
        @Nullable
        UUID targetUuid = targetCp != null ? targetCp.getUniqueId() : null;

        List<String> accepts = req.getAccepts();
        List<String> denies = req.getDenies();

        String keyToRemove = target;

        switch (req.getType()) {
            case START_WAR:
                processStartWar(requester, requestClan, targetClan, accepts, denies);
                break;
            case END_WAR:
                processEndWar(requester, requestClan, targetClan, accepts, denies);
                break;
            case CREATE_ALLY:
                processCreateAlly(requester, requestClan, targetClan, accepts, denies);
                break;
            case BREAK_RIVALRY:
                processBreakRivalry(requester, requestClan, targetClan, accepts, denies);
                break;
            case DEMOTE:
            case PROMOTE:
                if (!req.votingFinished() || targetUuid == null) {
                    return;
                }
                keyToRemove = requestClan.getTag();

                if (req.getType() == ClanRequest.DEMOTE) {
                    processDemote(req, requestClan, targetUuid, denies);
                }
                if (req.getType() == ClanRequest.PROMOTE) {
                    processPromote(req, requestClan, targetUuid, denies);
                }
                break;
            case DISBAND:
                if (!req.votingFinished()) {
                    return;
                }
                processDisband(requester, requestClan, denies);
                break;
            case RENAME:
                if (!req.votingFinished()) {
                    return;
                }
                processRename(req);
                break;
            default:
                return;
        }

        requests.remove(keyToRemove);
        removeFromRedis(keyToRemove, "processed_" + req.getType().name().toLowerCase());
        SimpleClans.getInstance().getServer().getPluginManager().callEvent(new RequestFinishedEvent(req));
        req.cleanVotes();
    }

    private void processRename(Request request) {
        if (request.getDenies().isEmpty()) {
            request.getClan().setName(request.getTarget());
        } else {
            String deniers = String.join(", ", request.getDenies());
            request.getClan().leaderAnnounce(RED + lang("rename.refused", deniers));
        }
    }

    private void processDisband(ClanPlayer requester, Clan requestClan, List<String> denies) {
        if (denies.isEmpty()) {
            requestClan.disband(requester.toPlayer(), true, false);
        } else {
            String deniers = String.join(", ", denies);
            requestClan.leaderAnnounce(RED + lang("clan.deletion", deniers));
        }
    }

    private void processPromote(Request req, Clan requestClan, UUID targetPlayer, List<String> denies) {
        String promotedName = req.getTarget();
        if (denies.isEmpty()) {
            requestClan.addBb(lang("leaders"), lang("promoted.to.leader", promotedName));
            requestClan.promote(targetPlayer);
        } else {
            String deniers = String.join(", ", denies);
            requestClan.leaderAnnounce(RED + lang("denied.the.promotion", deniers, promotedName));
        }
    }

    private void processDemote(Request req, Clan requestClan, UUID targetPlayer, List<String> denies) {
        String demotedName = req.getTarget();
        if (denies.isEmpty()) {
            requestClan.addBb(lang("leaders"), lang("demoted.back.to.member", demotedName));
            requestClan.demote(targetPlayer);
        } else {
            String deniers = String.join(", ", denies);
            requestClan.leaderAnnounce(
                    RED + lang("denied.demotion", deniers, demotedName));
        }
    }

    private void processBreakRivalry(ClanPlayer requester, Clan requestClan, @Nullable Clan targetClan,
                                     List<String> accepts, List<String> denies) {
        if (targetClan != null && requestClan != null) {
            if (!accepts.isEmpty()) {
                requestClan.removeRival(targetClan);
                targetClan.addBb(requester.getName(), lang("broken.the.rivalry", accepts.get(0), requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("broken.the.rivalry.with", requester.getName(), targetClan.getName()));
            } else {
                targetClan.addBb(requester.getName(), lang("denied.to.make.peace", denies.get(0), requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("peace.agreement.denied", targetClan.getName()));
            }
        }
    }

    private void processCreateAlly(ClanPlayer requester, Clan requestClan, @Nullable Clan targetClan,
                                   List<String> accepts, List<String> denies) {
        if (targetClan != null && requestClan != null) {
            if (!accepts.isEmpty()) {
                requestClan.addAlly(targetClan);

                targetClan.addBb(requester.getName(), lang("accepted.an.alliance", accepts.get(0), requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("created.an.alliance", requester.getName(), targetClan.getName()));
            } else {
                targetClan.addBb(requester.getName(), lang("denied.an.alliance", denies.get(0), requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("the.alliance.was.denied", targetClan.getName()));
            }
        }
    }

    private void processEndWar(ClanPlayer requester, Clan requestClan, @Nullable Clan targetClan, List<String> accepts,
                               List<String> denies) {
        if (requestClan != null && targetClan != null) {
            if (!accepts.isEmpty()) {
                War war = plugin.getProtectionManager().getWar(requestClan, targetClan);
                plugin.getProtectionManager().removeWar(war, WarEndEvent.Reason.REQUEST);
                requestClan.removeWarringClan(targetClan);
                targetClan.removeWarringClan(requestClan);

                targetClan.addBb(requester.getName(), lang("you.are.no.longer.at.war", accepts.get(0), requestClan.getColorTag()));
                requestClan.addBb(requester.getName(), lang("you.are.no.longer.at.war", requestClan.getName(), targetClan.getColorTag()));
            } else {
                targetClan.addBb(requester.getName(), lang("denied.war.end", denies.get(0), requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("end.war.denied", targetClan.getName()));
            }
        }
    }

    private void processStartWar(ClanPlayer requester, Clan requestClan, @Nullable Clan targetClan,
                                 List<String> accepts, List<String> denies) {
        if (requestClan != null && targetClan != null) {
            if (!accepts.isEmpty()) {
                plugin.getProtectionManager().addWar(requester, requestClan, targetClan);
            } else {
                targetClan.addBb(requester.getName(), lang("denied.war.req", denies.get(0),
                        requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("end.war.denied",
                        targetClan.getName()));
            }
        }
    }

    /**
     * End a pending request prematurely
     *
     * @param playerName the Player signing off
     */
    public void endPendingRequest(String playerName) {
        for (Request req : new LinkedList<>(requests.values())) {
            for (ClanPlayer cp : req.getAcceptors()) {
                if (cp.getName().equalsIgnoreCase(playerName)) {
                    String key = req.getClan().getTag();
                    req.getClan().leaderAnnounce(lang("signed.off.request.cancelled", RED + playerName, req.getType()));
                    requests.remove(key);
                    removeFromRedis(key, "player_signed_off");
                    break;
                }
            }
        }

    }

    public void removeRequest(@NotNull String keyOrTarget) {
        Iterator<Map.Entry<String, Request>> iterator = requests.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Request> entry = iterator.next();
            final String key = entry.getKey();
            final String target = entry.getValue().getTarget();
            if (keyOrTarget.equals(key) || keyOrTarget.equals(target)) {
                entry.getValue().cleanVotes();
                iterator.remove();
                removeFromRedis(key, "removed");
            }
        }
    }

    /**
     * Starts the task that asks for the votes of all requests
     */
    public void askerTask() {
        new BukkitRunnable() {

            @Override
            public void run() {
                for (Iterator<Map.Entry<String, Request>> iter = requests.entrySet().iterator(); iter.hasNext(); ) {
                    Map.Entry<String, Request> entry = iter.next();
                    Request req = entry.getValue();

                    if (req == null) {
                        continue;
                    }

                    if (req.reachedRequestLimit()) {
                        String key = entry.getKey();
                        iter.remove();
                        removeFromRedis(key, "expired");
                        continue;
                    }

                    ask(req);
                    req.incrementAskCount();
                }
            }
        }.runTaskTimerAsynchronously(plugin, 0, plugin.getSettingsManager().getSeconds(REQUEST_FREQUENCY));
    }

    /**
     * Asks a request to players for votes
     *
     * @param req the Request
     */
    public void ask(final Request req) {
        // Skip Redis notification on first ask (askCount == 0) because publishNewRequest already sent it
        boolean skipRemote = isRedisEnabled() && req.getAskCount() == 0;
        
        String message = lang("request.message", req.getClan().getColorTag(), req.getMsg());
        ArrayList<Player> localRecipients = new ArrayList<>();
        ArrayList<String> remoteRecipients = new ArrayList<>();
        
        if (req.getType() == ClanRequest.INVITE) {
            Player localPlayer = Bukkit.getPlayerExact(req.getTarget());
            if (localPlayer != null) {
                localRecipients.add(localPlayer);
            } else if (isRedisEnabled() && !skipRemote) {
                // Player is on another server - send message via Redis
                plugin.getProxyManager().sendMessage(req.getTarget(), message);
            }
        } else {
            // For non-invite requests (ally, war, etc.), check each acceptor
            List<ClanPlayer> acceptors = req.getAcceptors();
            
            if (acceptors != null) {
                for (ClanPlayer cp : acceptors) {
                    if (cp.getVote() == null) {
                        Player localPlayer = cp.toPlayer();
                        if (localPlayer != null) {
                            localRecipients.add(localPlayer);
                        } else if (isRedisEnabled() && !skipRemote && plugin.getProxyManager().isOnline(cp.getName())) {
                            // Player is on another server
                            remoteRecipients.add(cp.getName());
                        }
                    }
                }
            }
        }

        // Send to local players
        for (Player recipient : localRecipients) {
            if (recipient != null) {
                recipient.spigot().sendMessage(ChatUtils.toBaseComponents(recipient, message));
            }
        }
        
        // Send to remote players via Redis
        for (String remoteName : remoteRecipients) {
            plugin.getProxyManager().sendMessage(remoteName, message);
        }

        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new RequestEvent(req)));
    }
}
