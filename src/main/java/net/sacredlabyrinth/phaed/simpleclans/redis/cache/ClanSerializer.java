package net.sacredlabyrinth.phaed.simpleclans.redis.cache;

import com.google.gson.*;
import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.Helper;
import net.sacredlabyrinth.phaed.simpleclans.events.ClanBalanceUpdateEvent;
import net.sacredlabyrinth.phaed.simpleclans.loggers.BankLogger;
import net.sacredlabyrinth.phaed.simpleclans.loggers.BankOperator;
import net.sacredlabyrinth.phaed.simpleclans.utils.ChatUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes and deserializes Clan objects for Redis cache.
 * Only stores essential data that needs to be cached - members are managed separately.
 */
public class ClanSerializer implements JsonSerializer<Clan>, JsonDeserializer<Clan> {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Clan.class, new ClanSerializer())
            .create();

    /**
     * Serializes a Clan to JSON string.
     *
     * @param clan the clan to serialize
     * @return JSON string representation
     */
    @NotNull
    public static String serialize(@NotNull Clan clan) {
        return GSON.toJson(clan);
    }

    /**
     * Deserializes a Clan from JSON string.
     *
     * @param json the JSON string
     * @return the deserialized Clan, or null if invalid
     */
    @Nullable
    public static Clan deserialize(@NotNull String json) {
        try {
            return GSON.fromJson(json, Clan.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    @Override
    public JsonElement serialize(Clan clan, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject obj = new JsonObject();
        
        // Core identity
        obj.addProperty("tag", clan.getTag());
        obj.addProperty("colorTag", clan.getColorTag());
        obj.addProperty("name", clan.getName());
        
        // Status
        obj.addProperty("verified", clan.isVerified());
        obj.addProperty("friendlyFire", clan.isFriendlyFire());
        
        // Description
        obj.addProperty("description", clan.getDescription());
        
        // Financial
        obj.addProperty("balance", clan.getBalance());
        obj.addProperty("fee", clan.getMemberFee());
        obj.addProperty("feeEnabled", clan.isMemberFeeEnabled());
        
        // Timestamps
        obj.addProperty("founded", clan.getFounded());
        obj.addProperty("lastUsed", clan.getLastUsed());
        
        // Relations (packed format for efficiency)
        obj.addProperty("packedAllies", clan.getPackedAllies());
        obj.addProperty("packedRivals", clan.getPackedRivals());
        
        // Flags
        obj.addProperty("flags", clan.getFlags());
        
        // Ranks (already has JSON representation in Helper)
        obj.addProperty("ranksJson", Helper.ranksToJson(clan.getRanks(), clan.getDefaultRank()));
        
        return obj;
    }

    @Override
    public Clan deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonObject obj = json.getAsJsonObject();
        
        Clan clan = new Clan();
        
        // Core identity
        clan.setTag(getStringOrDefault(obj, "tag", ""));
        clan.setColorTag(ChatUtils.parseColors(getStringOrDefault(obj, "colorTag", "")));
        clan.setName(getStringOrDefault(obj, "name", ""));
        
        // Status
        clan.setVerified(getBooleanOrDefault(obj, "verified", false));
        clan.setFriendlyFire(getBooleanOrDefault(obj, "friendlyFire", false));
        
        // Description
        clan.setDescription(getStringOrDefault(obj, "description", ""));
        
        // Financial - use LOADING cause to avoid triggering events/updates
        clan.setBalance(BankOperator.INTERNAL, ClanBalanceUpdateEvent.Cause.LOADING, 
                BankLogger.Operation.SET, getDoubleOrDefault(obj, "balance", 0.0));
        clan.setMemberFee(getDoubleOrDefault(obj, "fee", 0.0));
        clan.setMemberFeeEnabled(getBooleanOrDefault(obj, "feeEnabled", false));
        
        // Timestamps
        clan.setFounded(getLongOrDefault(obj, "founded", System.currentTimeMillis()));
        clan.setLastUsed(getLongOrDefault(obj, "lastUsed", System.currentTimeMillis()));
        
        // Relations
        clan.setPackedAllies(getStringOrDefault(obj, "packedAllies", ""));
        clan.setPackedRivals(getStringOrDefault(obj, "packedRivals", ""));
        
        // Flags
        clan.setFlags(getStringOrDefault(obj, "flags", ""));
        
        // Ranks
        String ranksJson = getStringOrDefault(obj, "ranksJson", "");
        if (!ranksJson.isEmpty()) {
            clan.setRanks(Helper.ranksFromJson(ranksJson));
            clan.setDefaultRank(Helper.defaultRankFromJson(ranksJson));
        }
        
        return clan;
    }

    private static String getStringOrDefault(JsonObject obj, String key, String defaultValue) {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsString() : defaultValue;
    }

    private static boolean getBooleanOrDefault(JsonObject obj, String key, boolean defaultValue) {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsBoolean() : defaultValue;
    }

    private static double getDoubleOrDefault(JsonObject obj, String key, double defaultValue) {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsDouble() : defaultValue;
    }

    private static long getLongOrDefault(JsonObject obj, String key, long defaultValue) {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsLong() : defaultValue;
    }
}
