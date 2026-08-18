package com.wm.streamhub.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.wm.streamhub.model.ServerProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** All persisted state lives here (servers, playback tuning, support settings). */
public class Prefs {

    private static final String FILE = "streamhub_prefs";

    // Buffer profiles
    public static final int PROFILE_AUTO = 0;
    public static final int PROFILE_ANTI_BUFFER = 1;
    public static final int PROFILE_BALANCED = 2;
    public static final int PROFILE_LOW_LATENCY = 3;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------------- servers ----------------

    public List<ServerProfile> getServers() {
        List<ServerProfile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("servers", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(ServerProfile.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, new Comparator<ServerProfile>() {
            @Override
            public int compare(ServerProfile a, ServerProfile b) {
                return a.priority - b.priority;
            }
        });
        return out;
    }

    public void saveServers(List<ServerProfile> servers) {
        JSONArray arr = new JSONArray();
        for (ServerProfile s : servers) {
            try {
                arr.put(s.toJson());
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString("servers", arr.toString()).apply();
    }

    public void upsertServer(ServerProfile server) {
        List<ServerProfile> all = getServers();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(server.id)) {
                all.set(i, server);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            server.priority = all.size();
            all.add(server);
        }
        saveServers(all);
    }

    public void deleteServer(String id) {
        List<ServerProfile> all = getServers();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) all.remove(i);
        }
        saveServers(all);
    }

    public ServerProfile getServer(String id) {
        for (ServerProfile s : getServers()) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    public String getActiveServerId() {
        return sp.getString("activeServer", "");
    }

    public void setActiveServerId(String id) {
        sp.edit().putString("activeServer", id).apply();
    }

    // ---------------- playback tuning ----------------

    /** Master switch for bandwidth-aware behaviour. */
    public boolean isAdaptiveEnabled() {
        return sp.getBoolean("adaptive", true);
    }

    public void setAdaptiveEnabled(boolean v) {
        sp.edit().putBoolean("adaptive", v).apply();
    }

    public int getBufferProfile() {
        return sp.getInt("bufferProfile", PROFILE_AUTO);
    }

    public void setBufferProfile(int v) {
        sp.edit().putInt("bufferProfile", v).apply();
    }

    /** 0 = unlimited; otherwise a hard ceiling in kbps chosen by the user. */
    public int getMaxBitrateKbps() {
        return sp.getInt("maxBitrate", 0);
    }

    public void setMaxBitrateKbps(int v) {
        sp.edit().putInt("maxBitrate", v).apply();
    }

    public boolean isShowStats() {
        return sp.getBoolean("showStats", false);
    }

    public void setShowStats(boolean v) {
        sp.edit().putBoolean("showStats", v).apply();
    }

    public boolean isAutoFailover() {
        return sp.getBoolean("autoFailover", true);
    }

    public void setAutoFailover(boolean v) {
        sp.edit().putBoolean("autoFailover", v).apply();
    }

    public boolean isHwDecodeFallback() {
        return sp.getBoolean("swFallback", true);
    }

    public void setHwDecodeFallback(boolean v) {
        sp.edit().putBoolean("swFallback", v).apply();
    }

    /** Remembered estimate so the very first stream already starts at a sane quality. */
    public long getLastBandwidthBps() {
        return sp.getLong("lastBandwidth", 0L);
    }

    public void setLastBandwidthBps(long v) {
        if (v > 0) sp.edit().putLong("lastBandwidth", v).apply();
    }

    // ---------------- favorites & resume ----------------

    public List<com.wm.streamhub.model.StreamItem> getFavoriteItems() {
        List<com.wm.streamhub.model.StreamItem> out = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(sp.getString("favItems", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(com.wm.streamhub.model.StreamItem.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public boolean isFavorite(String key) {
        return getFavorites().contains(key);
    }

    public Set<String> getFavorites() {
        return new HashSet<>(sp.getStringSet("favorites", new HashSet<String>()));
    }

    /** Adds or removes a favourite, keeping the key index and the item copy in sync. */
    public boolean toggleFavorite(com.wm.streamhub.model.StreamItem item) {
        String key = item.favKey();
        Set<String> keys = getFavorites();
        List<com.wm.streamhub.model.StreamItem> items = getFavoriteItems();
        boolean nowFavorite;
        if (keys.remove(key)) {
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).favKey().equals(key)) items.remove(i);
            }
            nowFavorite = false;
        } else {
            keys.add(key);
            items.add(item);
            nowFavorite = true;
        }
        org.json.JSONArray arr = new org.json.JSONArray();
        for (com.wm.streamhub.model.StreamItem s : items) {
            try {
                arr.put(s.toJson());
            } catch (Exception ignored) {
            }
        }
        sp.edit().putStringSet("favorites", keys).putString("favItems", arr.toString()).apply();
        return nowFavorite;
    }

    public String getLastPlayedJson() {
        return sp.getString("lastPlayed", "");
    }

    public void setLastPlayedJson(String json) {
        sp.edit().putString("lastPlayed", json).apply();
    }

    // ---------------- support / messaging ----------------

    public String getDeviceId() {
        String id = sp.getString("deviceId", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            sp.edit().putString("deviceId", id).apply();
        }
        return id;
    }

    public String getClientName() {
        return sp.getString("clientName", "");
    }

    public void setClientName(String v) {
        sp.edit().putString("clientName", v).apply();
    }

    public String getChatBaseUrl() {
        return sp.getString("chatUrl", BuildDefaults.CHAT_BASE_URL);
    }

    public void setChatBaseUrl(String v) {
        sp.edit().putString("chatUrl", v == null ? "" : v.trim()).apply();
    }

    public String getChatApiKey() {
        return sp.getString("chatKey", BuildDefaults.CHAT_API_KEY);
    }

    public void setChatApiKey(String v) {
        sp.edit().putString("chatKey", v == null ? "" : v.trim()).apply();
    }

    public String getSupportWhatsApp() {
        return sp.getString("supWa", BuildDefaults.SUPPORT_WHATSAPP);
    }

    public void setSupportWhatsApp(String v) {
        sp.edit().putString("supWa", v == null ? "" : v.trim()).apply();
    }

    public String getSupportTelegram() {
        return sp.getString("supTg", BuildDefaults.SUPPORT_TELEGRAM);
    }

    public void setSupportTelegram(String v) {
        sp.edit().putString("supTg", v == null ? "" : v.trim()).apply();
    }

    public String getSupportEmail() {
        return sp.getString("supMail", BuildDefaults.SUPPORT_EMAIL);
    }

    public void setSupportEmail(String v) {
        sp.edit().putString("supMail", v == null ? "" : v.trim()).apply();
    }

    /** Cached local copy of the conversation so it survives restarts. */
    public String getChatCacheJson() {
        return sp.getString("chatCache", "[]");
    }

    public void setChatCacheJson(String json) {
        sp.edit().putString("chatCache", json).apply();
    }

    public long getLastNoticeSeen() {
        return sp.getLong("lastNotice", 0L);
    }

    public void setLastNoticeSeen(long v) {
        sp.edit().putLong("lastNotice", v).apply();
    }

    // ---------------- generic cache ----------------

    public void putCache(String key, String value) {
        sp.edit().putString("cache_" + key, value)
                .putLong("cacheTime_" + key, System.currentTimeMillis()).apply();
    }

    public String getCache(String key, long maxAgeMs) {
        long t = sp.getLong("cacheTime_" + key, 0L);
        if (t == 0 || System.currentTimeMillis() - t > maxAgeMs) return null;
        return sp.getString("cache_" + key, null);
    }

    public void clearCache() {
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith("cache_") || k.startsWith("cacheTime_")) e.remove(k);
        }
        e.apply();
    }

    public JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("device", getDeviceId());
            o.put("servers", getServers().size());
            o.put("adaptive", isAdaptiveEnabled());
            o.put("bufferProfile", getBufferProfile());
        } catch (Exception ignored) {
        }
        return o;
    }
}
