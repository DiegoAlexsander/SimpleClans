package net.sacredlabyrinth.phaed.simpleclans.redis.cache;

import com.google.gson.*;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.Helper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Locale;
import java.util.UUID;

/**
 * Serializes and deserializes ClanPlayer objects for Redis cache.
 * Only stores essential data - clan association is stored separately.
 */
public class ClanPlayerSerializer implements JsonSerializer<ClanPlayer>, JsonDeserializer<ClanPlayer> {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(ClanPlayer.class, new ClanPlayerSerializer())
            .create();

    /**
     * Serializes a ClanPlayer to JSON string.
     *
     * @param cp the ClanPlayer to serialize
     * @return JSON string representation
     */
    @NotNull
    public static String serialize(@NotNull ClanPlayer cp) {
        return GSON.toJson(cp);
    }

    /**
     * Deserializes a ClanPlayer from JSON string.
     *
     * @param json the JSON string
     * @return the deserialized ClanPlayer, or null if invalid
     */
    @Nullable
    public static ClanPlayer deserialize(@NotNull String json) {
        try {
            return GSON.fromJson(json, ClanPlayer.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    @Override
    public JsonElement serialize(ClanPlayer cp, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject obj = new JsonObject();
        
        // Identity
        obj.addProperty("uuid", cp.getUniqueId() != null ? cp.getUniqueId().toString() : null);
        obj.addProperty("name", cp.getName());
        
        // Clan association (tag only, clan is loaded separately)
        obj.addProperty("tag", cp.getTag());
        
        // Status
        obj.addProperty("leader", cp.isLeader());
        obj.addProperty("trusted", cp.isTrusted());
        obj.addProperty("friendlyFire", cp.isFriendlyFire());
        
        // Stats
        obj.addProperty("neutralKills", cp.getNeutralKills());
        obj.addProperty("rivalKills", cp.getRivalKills());
        obj.addProperty("civilianKills", cp.getCivilianKills());
        obj.addProperty("allyKills", cp.getAllyKills());
        obj.addProperty("deaths", cp.getDeaths());
        
        // Timestamps
        obj.addProperty("lastSeen", cp.getLastSeen());
        obj.addProperty("joinDate", cp.getJoinDate());
        
        // Packed data
        obj.addProperty("packedPastClans", cp.getPackedPastClans());
        obj.addProperty("resignTimes", Helper.resignTimesToJson(cp.getResignTimes()));
        
        // Flags
        obj.addProperty("flags", cp.getFlags());
        
        // Locale
        obj.addProperty("locale", Helper.toLanguageTag(cp.getLocale()));
        
        // Chat mutes
        obj.addProperty("mutedAlly", cp.isMutedAlly());
        obj.addProperty("muted", cp.isMuted());
        
        return obj;
    }

    @Override
    public ClanPlayer deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonObject obj = json.getAsJsonObject();
        
        ClanPlayer cp = new ClanPlayer();
        
        // Identity
        String uuidStr = getStringOrNull(obj, "uuid");
        if (uuidStr != null) {
            try {
                cp.setUniqueId(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {
            }
        }
        cp.setName(getStringOrDefault(obj, "name", ""));
        
        // Status
        cp.setLeader(getBooleanOrDefault(obj, "leader", false));
        cp.setTrusted(getBooleanOrDefault(obj, "trusted", false));
        cp.setFriendlyFire(getBooleanOrDefault(obj, "friendlyFire", false));
        
        // Stats
        cp.setNeutralKills(getIntOrDefault(obj, "neutralKills", 0));
        cp.setRivalKills(getIntOrDefault(obj, "rivalKills", 0));
        cp.setCivilianKills(getIntOrDefault(obj, "civilianKills", 0));
        cp.setAllyKills(getIntOrDefault(obj, "allyKills", 0));
        cp.setDeaths(getIntOrDefault(obj, "deaths", 0));
        
        // Timestamps
        cp.setLastSeen(getLongOrDefault(obj, "lastSeen", System.currentTimeMillis()));
        cp.setJoinDate(getLongOrDefault(obj, "joinDate", System.currentTimeMillis()));
        
        // Packed data
        cp.setPackedPastClans(getStringOrDefault(obj, "packedPastClans", ""));
        String resignTimesJson = getStringOrDefault(obj, "resignTimes", "");
        if (!resignTimesJson.isEmpty()) {
            cp.setResignTimes(Helper.resignTimesFromJson(resignTimesJson));
        }
        
        // Flags
        cp.setFlags(getStringOrDefault(obj, "flags", ""));
        
        // Locale
        String localeStr = getStringOrNull(obj, "locale");
        if (localeStr != null) {
            cp.setLocale(Helper.forLanguageTag(localeStr));
        }
        
        // Chat mutes - use deprecated setters (no Channel enum alternative for set)
        cp.setMutedAlly(getBooleanOrDefault(obj, "mutedAlly", false));
        cp.setMuted(getBooleanOrDefault(obj, "muted", false));
        
        // Note: Clan association is NOT set here - it must be resolved by the caller
        // using the "tag" field and looking up the clan
        
        return cp;
    }

    @Nullable
    private static String getStringOrNull(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsString() : null;
    }

    private static String getStringOrDefault(JsonObject obj, String key, String defaultValue) {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsString() : defaultValue;
    }

    private static boolean getBooleanOrDefault(JsonObject obj, String key, boolean defaultValue) {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsBoolean() : defaultValue;
    }

    private static int getIntOrDefault(JsonObject obj, String key, int defaultValue) {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsInt() : defaultValue;
    }

    private static long getLongOrDefault(JsonObject obj, String key, long defaultValue) {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsLong() : defaultValue;
    }

    /**
     * Gets the clan tag from a serialized ClanPlayer JSON.
     * Useful for resolving clan association without full deserialization.
     *
     * @param json the JSON string
     * @return the clan tag, or null if not present
     */
    @Nullable
    public static String getClanTag(@NotNull String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return getStringOrNull(obj, "tag");
        } catch (Exception e) {
            return null;
        }
    }
}
