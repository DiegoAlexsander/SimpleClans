package net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.sacredlabyrinth.phaed.simpleclans.*;
import net.sacredlabyrinth.phaed.simpleclans.managers.ClanManager;
import net.sacredlabyrinth.phaed.simpleclans.managers.RequestManager;
import net.sacredlabyrinth.phaed.simpleclans.redis.pubsub.MessageHandler;
import net.sacredlabyrinth.phaed.simpleclans.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;

/**
 * Handles request-related Pub/Sub messages.
 * <p>
 * This handler processes the following message types:
 * <ul>
 *   <li>{@code request_new} - A new request was created</li>
 *   <li>{@code request_vote} - A vote was cast on a request</li>
 *   <li>{@code request_remove} - A request was removed/processed</li>
 *   <li>{@code request_notify} - Notify a specific player about a pending request</li>
 * </ul>
 * 
 * @since 2.0
 */
public class RequestHandler implements MessageHandler {

    private static final Gson GSON = new Gson();
    
    private final SimpleClans plugin;
    private final ClanManager clanManager;

    public RequestHandler(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
        this.clanManager = plugin.getClanManager();
    }

    @Override
    public void handle(@NotNull String message) {
        try {
            JsonObject json = GSON.fromJson(message, JsonObject.class);
            String action = json.has("action") ? json.get("action").getAsString() : null;
            
            if (action == null) {
                return;
            }
            
            switch (action) {
                case "request_new":
                    handleNewRequest(json);
                    break;
                case "request_vote":
                    handleVote(json);
                    break;
                case "request_remove":
                    handleRemove(json);
                    break;
                case "request_notify":
                    handleNotify(json);
                    break;
                default:
                    // Unknown action, ignore
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error processing request message: " + message, e);
        }
    }

    /**
     * Handles a new request notification.
     * This notifies relevant players on this server about a new request.
     */
    private void handleNewRequest(JsonObject json) {
        String requestKey = getStringOrNull(json, "key");
        String requestType = getStringOrNull(json, "type");
        String clanTag = getStringOrNull(json, "clanTag");
        String targetClanTag = getStringOrNull(json, "targetClanTag");
        String targetPlayer = getStringOrNull(json, "targetPlayer");
        String requestMessage = getStringOrNull(json, "message");
        
        if (requestKey == null || requestType == null) {
            plugin.getLogger().warning("[Redis] Received invalid new request message: missing key or type");
            return;
        }
        
        plugin.getLogger().info(() -> String.format(
                "[Redis] New request notification received - Key: %s, Type: %s, ClanTag: %s, TargetClan: %s, TargetPlayer: %s",
                requestKey, requestType, clanTag, targetClanTag, targetPlayer));
        
        // Schedule on main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            ClanRequest type;
            try {
                type = ClanRequest.valueOf(requestType);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[Redis] Invalid request type: " + requestType);
                return;
            }
            
            if (type == ClanRequest.INVITE) {
                // Notify the invited player if they're on this server
                if (targetPlayer != null) {
                    Player player = Bukkit.getPlayerExact(targetPlayer);
                    if (player != null && requestMessage != null) {
                        plugin.getLogger().info(() -> String.format(
                                "[Redis] Notifying player %s about invite request",
                                targetPlayer));
                        Clan clan = clanTag != null ? clanManager.getClan(clanTag) : null;
                        String displayTag = clan != null ? clan.getColorTag() : clanTag;
                        String fullMessage = lang("request.message", displayTag, requestMessage);
                        player.spigot().sendMessage(ChatUtils.toBaseComponents(player, fullMessage));
                    } else {
                        plugin.getLogger().fine(() -> String.format(
                                "[Redis] Target player %s not on this server for invite",
                                targetPlayer));
                    }
                }
            } else if (isInterClanRequest(type)) {
                // For inter-clan requests (ally, rivalry, war), notify the TARGET clan's leaders
                if (targetClanTag != null) {
                    Clan targetClan = clanManager.getClan(targetClanTag);
                    Clan requestingClan = clanTag != null ? clanManager.getClan(clanTag) : null;
                    String displayTag = requestingClan != null ? requestingClan.getColorTag() : (clanTag != null ? clanTag : "");
                    
                    plugin.getLogger().info(() -> String.format(
                            "[Redis Debug] Inter-clan request - TargetClan: %s (found: %s), RequestingClan: %s (found: %s), Message: %s",
                            targetClanTag, targetClan != null, clanTag, requestingClan != null, requestMessage != null ? "present" : "NULL"));
                    
                    if (targetClan != null && requestMessage != null) {
                        String fullMessage = lang("request.message", displayTag, requestMessage);
                        int notified = 0;
                        List<String> leadersInfo = new ArrayList<>();
                        for (ClanPlayer leader : targetClan.getLeaders()) {
                            Player player = leader.toPlayer();
                            boolean hasVoted = leader.getVote() != null;
                            leadersInfo.add(String.format("%s(online:%s,voted:%s)", leader.getName(), player != null, hasVoted));
                            if (!hasVoted && player != null) {
                                player.spigot().sendMessage(ChatUtils.toBaseComponents(player, fullMessage));
                                notified++;
                            }
                        }
                    } else {
                        // Cannot notify - target clan or message not available
                    }
                } else {
                }
            } else {
                // For internal clan requests (demote, promote, disband, rename), notify own leaders
                if (clanTag != null) {
                    Clan clan = clanManager.getClan(clanTag);
                    if (clan != null && requestMessage != null) {
                        String fullMessage = lang("request.message", clan.getColorTag(), requestMessage);
                        int notified = 0;
                        for (ClanPlayer leader : clan.getLeaders()) {
                            // Skip the target player for DEMOTE requests (they shouldn't vote on their own demotion)
                            if (type == ClanRequest.DEMOTE && targetPlayer != null && 
                                leader.getName().equalsIgnoreCase(targetPlayer)) {
                                continue;
                            }
                            if (leader.getVote() == null) {
                                Player player = leader.toPlayer();
                                if (player != null) {
                                    player.spigot().sendMessage(ChatUtils.toBaseComponents(player, fullMessage));
                                    notified++;
                                }
                            }
                        }
                    }
                }
            }
        });
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
     * Handles a vote notification.
     * This updates the local request state when a vote is cast on another server
     * and processes the results if voting is finished.
     */
    private void handleVote(JsonObject json) {
        String requestKey = getStringOrNull(json, "key");
        String voterName = getStringOrNull(json, "voter");
        String voteValue = getStringOrNull(json, "vote");
        
        if (requestKey == null || voterName == null || voteValue == null) {
            plugin.getLogger().warning("[Redis] Received invalid vote message: missing required fields");
            return;
        }
        
        VoteResult vote;
        try {
            vote = VoteResult.valueOf(voteValue);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[Redis] Received invalid vote value: " + voteValue);
            return;
        }
        
        // Schedule on main thread to update local request and process results
        Bukkit.getScheduler().runTask(plugin, () -> {
            RequestManager requestManager = plugin.getRequestManager();
            // Apply the remote vote and process results
            requestManager.applyRemoteVote(requestKey, voterName, vote);
        });
    }

    /**
     * Handles a request removal notification.
     * This removes the local request when it's processed on another server.
     */
    private void handleRemove(JsonObject json) {
        String requestKey = getStringOrNull(json, "key");
        String reason = getStringOrNull(json, "reason");
        
        if (requestKey == null) {
            return;
        }
        
        // Schedule on main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            RequestManager requestManager = plugin.getRequestManager();
            // Remove from local memory - this stops the askerTask from sending more messages
            requestManager.removeLocalRequest(requestKey);
        });
    }

    /**
     * Handles a notify request.
     * This notifies a specific player about a pending request.
     */
    private void handleNotify(JsonObject json) {
        String playerUuid = getStringOrNull(json, "playerUuid");
        String playerName = getStringOrNull(json, "playerName");
        String requestKey = getStringOrNull(json, "key");
        String requestMessage = getStringOrNull(json, "message");
        String clanTag = getStringOrNull(json, "clanTag");
        
        if (requestKey == null || requestMessage == null) {
            return;
        }
        
        // Schedule on main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = null;
            
            // Try to find player by UUID first
            if (playerUuid != null) {
                try {
                    UUID uuid = UUID.fromString(playerUuid);
                    player = Bukkit.getPlayer(uuid);
                } catch (IllegalArgumentException ignored) {
                    // Invalid UUID
                }
            }
            
            // Fall back to name
            if (player == null && playerName != null) {
                player = Bukkit.getPlayerExact(playerName);
            }
            
            if (player != null) {
                Clan clan = clanTag != null ? clanManager.getClan(clanTag) : null;
                String displayTag = clan != null ? clan.getColorTag() : (clanTag != null ? clanTag : "");
                String fullMessage = lang("request.message", displayTag, requestMessage);
                player.spigot().sendMessage(ChatUtils.toBaseComponents(player, fullMessage));
            }
        });
    }

    private String getStringOrNull(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    // ==================== Static message builders ====================

    /**
     * Creates a new request notification message.
     *
     * @param key           the request key
     * @param type          the request type
     * @param clanTag       the clan tag (requesting clan)
     * @param targetClanTag the target clan tag (for inter-clan requests like ally, rivalry, war)
     * @param targetPlayer  the target player (for invites)
     * @param message       the request message
     * @return JSON message string
     */
    @NotNull
    public static String createNewRequestMessage(@NotNull String key, @NotNull ClanRequest type,
                                                  String clanTag, String targetClanTag, 
                                                  String targetPlayer, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "request_new");
        json.addProperty("key", key);
        json.addProperty("type", type.name());
        if (clanTag != null) json.addProperty("clanTag", clanTag);
        if (targetClanTag != null) json.addProperty("targetClanTag", targetClanTag);
        if (targetPlayer != null) json.addProperty("targetPlayer", targetPlayer);
        if (message != null) json.addProperty("message", message);
        return GSON.toJson(json);
    }

    /**
     * Creates a vote notification message.
     *
     * @param key       the request key
     * @param voterName the voter's name
     * @param vote      the vote result
     * @return JSON message string
     */
    @NotNull
    public static String createVoteMessage(@NotNull String key, @NotNull String voterName, @NotNull VoteResult vote) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "request_vote");
        json.addProperty("key", key);
        json.addProperty("voter", voterName);
        json.addProperty("vote", vote.name());
        return GSON.toJson(json);
    }

    /**
     * Creates a request removal message.
     *
     * @param key    the request key
     * @param reason the removal reason
     * @return JSON message string
     */
    @NotNull
    public static String createRemoveMessage(@NotNull String key, @NotNull String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "request_remove");
        json.addProperty("key", key);
        json.addProperty("reason", reason);
        return GSON.toJson(json);
    }

    /**
     * Creates a player notification message.
     *
     * @param playerUuid the player's UUID
     * @param playerName the player's name
     * @param key        the request key
     * @param message    the message to display
     * @param clanTag    the clan tag
     * @return JSON message string
     */
    @NotNull
    public static String createNotifyMessage(UUID playerUuid, String playerName, 
                                              @NotNull String key, @NotNull String message, String clanTag) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "request_notify");
        if (playerUuid != null) json.addProperty("playerUuid", playerUuid.toString());
        if (playerName != null) json.addProperty("playerName", playerName);
        json.addProperty("key", key);
        json.addProperty("message", message);
        if (clanTag != null) json.addProperty("clanTag", clanTag);
        return GSON.toJson(json);
    }
}
