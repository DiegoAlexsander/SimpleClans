package net.sacredlabyrinth.phaed.simpleclans.redis.request;

import com.google.gson.*;
import net.sacredlabyrinth.phaed.simpleclans.*;
import net.sacredlabyrinth.phaed.simpleclans.managers.ClanManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Serializes and deserializes Request objects to/from JSON for Redis storage.
 * <p>
 * This serializer converts Request objects into a compact JSON format that can be
 * stored in Redis and reconstructed on any server in the network.
 * </p>
 * 
 * @since 2.0
 */
public final class RequestSerializer {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(SerializableRequest.class, new RequestTypeAdapter())
            .create();

    private RequestSerializer() {
        // Utility class
    }

    /**
     * Serializes a Request to JSON string.
     *
     * @param request the request to serialize
     * @return JSON representation of the request
     */
    @NotNull
    public static String serialize(@NotNull Request request) {
        SerializableRequest sr = new SerializableRequest();
        sr.type = request.getType().name();
        sr.target = request.getTarget();
        sr.clanTag = request.getClan() != null ? request.getClan().getTag() : null;
        sr.message = request.getMsg();
        sr.requesterName = request.getRequester() != null ? request.getRequester().getName() : null;
        sr.requesterUuid = request.getRequester() != null ? request.getRequester().getUniqueId().toString() : null;
        
        // Serialize acceptors (only their UUIDs and names for reconstruction)
        sr.acceptors = new ArrayList<>();
        for (ClanPlayer cp : request.getAcceptors()) {
            AcceptorData ad = new AcceptorData();
            ad.uuid = cp.getUniqueId().toString();
            ad.name = cp.getName();
            ad.vote = cp.getVote() != null ? cp.getVote().name() : null;
            sr.acceptors.add(ad);
        }
        
        return GSON.toJson(sr);
    }

    /**
     * Deserializes a Request from JSON string.
     * <p>
     * Note: This method returns a SerializableRequest that needs to be converted
     * to a proper Request object using the ClanManager to resolve entities.
     * </p>
     *
     * @param json the JSON string
     * @return the deserialized SerializableRequest, or null if parsing fails
     */
    @Nullable
    public static SerializableRequest deserialize(@NotNull String json) {
        try {
            return GSON.fromJson(json, SerializableRequest.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /**
     * Converts a SerializableRequest back to a proper Request object.
     *
     * @param sr          the serializable request data
     * @param clanManager the clan manager to resolve entities
     * @return the reconstructed Request, or null if clan/requester cannot be resolved
     */
    @Nullable
    public static Request toRequest(@NotNull SerializableRequest sr, @NotNull ClanManager clanManager) {
        // Resolve clan
        Clan clan = clanManager.getClan(sr.clanTag);
        if (clan == null) {
            return null;
        }

        // Resolve requester
        ClanPlayer requester = null;
        if (sr.requesterUuid != null) {
            try {
                UUID uuid = UUID.fromString(sr.requesterUuid);
                requester = clanManager.getAnyClanPlayer(uuid);
            } catch (IllegalArgumentException ignored) {
                // Invalid UUID
            }
        }
        if (requester == null && sr.requesterName != null) {
            requester = clanManager.getAnyClanPlayer(sr.requesterName);
        }

        // Resolve request type
        ClanRequest type;
        try {
            type = ClanRequest.valueOf(sr.type);
        } catch (IllegalArgumentException e) {
            return null;
        }

        // Resolve acceptors and their votes
        List<ClanPlayer> acceptors = new ArrayList<>();
        // Store vote data to restore after Request creation (constructor calls cleanVotes())
        Map<String, VoteResult> votesToRestore = new HashMap<>();
        
        for (AcceptorData ad : sr.acceptors) {
            ClanPlayer cp = null;
            if (ad.uuid != null) {
                try {
                    UUID uuid = UUID.fromString(ad.uuid);
                    cp = clanManager.getAnyClanPlayer(uuid);
                } catch (IllegalArgumentException ignored) {
                    // Invalid UUID
                }
            }
            if (cp == null && ad.name != null) {
                cp = clanManager.getAnyClanPlayer(ad.name);
            }
            if (cp != null) {
                acceptors.add(cp);
                // Store vote to restore later
                if (ad.vote != null) {
                    try {
                        votesToRestore.put(cp.getName().toLowerCase(), VoteResult.valueOf(ad.vote));
                    } catch (IllegalArgumentException ignored) {
                        // Invalid vote
                    }
                }
            }
        }

        // Create the request (this will call cleanVotes() in constructor)
        Request request = new Request(type, acceptors, requester, sr.target, clan, sr.message);
        
        // Restore votes AFTER request creation
        for (ClanPlayer cp : acceptors) {
            VoteResult vote = votesToRestore.get(cp.getName().toLowerCase());
            if (vote != null) {
                request.vote(cp.getName(), vote);
            }
        }
        
        return request;
    }

    /**
     * Serializable representation of a Request for Redis storage.
     */
    public static class SerializableRequest {
        public String type;
        public String target;
        public String clanTag;
        public String message;
        public String requesterName;
        public String requesterUuid;
        public List<AcceptorData> acceptors = new ArrayList<>();
    }

    /**
     * Serializable representation of an acceptor with vote state.
     */
    public static class AcceptorData {
        public String uuid;
        public String name;
        public String vote; // null, "ACCEPT", or "DENY"
    }

    /**
     * Custom type adapter for SerializableRequest.
     */
    private static class RequestTypeAdapter implements JsonSerializer<SerializableRequest>, 
            JsonDeserializer<SerializableRequest> {

        @Override
        public JsonElement serialize(SerializableRequest src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", src.type);
            obj.addProperty("target", src.target);
            obj.addProperty("clanTag", src.clanTag);
            obj.addProperty("message", src.message);
            obj.addProperty("requesterName", src.requesterName);
            obj.addProperty("requesterUuid", src.requesterUuid);
            
            JsonArray acceptorsArray = new JsonArray();
            for (AcceptorData ad : src.acceptors) {
                JsonObject acceptorObj = new JsonObject();
                acceptorObj.addProperty("uuid", ad.uuid);
                acceptorObj.addProperty("name", ad.name);
                acceptorObj.addProperty("vote", ad.vote);
                acceptorsArray.add(acceptorObj);
            }
            obj.add("acceptors", acceptorsArray);
            
            return obj;
        }

        @Override
        public SerializableRequest deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) 
                throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            SerializableRequest sr = new SerializableRequest();
            
            sr.type = getStringOrNull(obj, "type");
            sr.target = getStringOrNull(obj, "target");
            sr.clanTag = getStringOrNull(obj, "clanTag");
            sr.message = getStringOrNull(obj, "message");
            sr.requesterName = getStringOrNull(obj, "requesterName");
            sr.requesterUuid = getStringOrNull(obj, "requesterUuid");
            
            if (obj.has("acceptors") && obj.get("acceptors").isJsonArray()) {
                JsonArray acceptorsArray = obj.getAsJsonArray("acceptors");
                for (JsonElement elem : acceptorsArray) {
                    if (elem.isJsonObject()) {
                        JsonObject acceptorObj = elem.getAsJsonObject();
                        AcceptorData ad = new AcceptorData();
                        ad.uuid = getStringOrNull(acceptorObj, "uuid");
                        ad.name = getStringOrNull(acceptorObj, "name");
                        ad.vote = getStringOrNull(acceptorObj, "vote");
                        sr.acceptors.add(ad);
                    }
                }
            }
            
            return sr;
        }

        private String getStringOrNull(JsonObject obj, String key) {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
        }
    }
}
