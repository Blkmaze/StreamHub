package com.wm.streamhub.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One IPTV source. Supports Xtream Codes panels and plain M3U/M3U8 playlists.
 */
public class ServerProfile {

    public static final String TYPE_XTREAM = "xtream";
    public static final String TYPE_M3U = "m3u";

    public String id = String.valueOf(System.currentTimeMillis());
    public String name = "";
    public String type = TYPE_XTREAM;

    /** Xtream: http://host:port  (no trailing slash) */
    public String host = "";
    public String username = "";
    public String password = "";

    /** M3U mode */
    public String m3uUrl = "";
    public String epgUrl = "";

    public boolean enabled = true;
    /** Lower number = tried first when failing over. */
    public int priority = 0;
    /** Prefer HLS (.m3u8) output for adaptive bitrate when the panel supports it. */
    public boolean preferHls = true;
    public String userAgent = "";

    public ServerProfile() {
    }

    public boolean isXtream() {
        return TYPE_XTREAM.equals(type);
    }

    public String normalizedHost() {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) return h;
        if (!h.startsWith("http://") && !h.startsWith("https://")) {
            h = "http://" + h;
        }
        while (h.endsWith("/")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    public String label() {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        if (isXtream()) return normalizedHost();
        return m3uUrl;
    }

    public String subtitle() {
        if (isXtream()) {
            return "Xtream · " + normalizedHost().replace("http://", "").replace("https://", "");
        }
        String u = m3uUrl == null ? "" : m3uUrl;
        if (u.length() > 46) u = u.substring(0, 44) + "…";
        return "M3U · " + u;
    }

    public boolean isValid() {
        if (isXtream()) {
            return !normalizedHost().isEmpty() && username != null && !username.trim().isEmpty();
        }
        return m3uUrl != null && m3uUrl.trim().length() > 8;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("type", type);
        o.put("host", host);
        o.put("username", username);
        o.put("password", password);
        o.put("m3uUrl", m3uUrl);
        o.put("epgUrl", epgUrl);
        o.put("enabled", enabled);
        o.put("priority", priority);
        o.put("preferHls", preferHls);
        o.put("userAgent", userAgent);
        return o;
    }

    public static ServerProfile fromJson(JSONObject o) {
        ServerProfile s = new ServerProfile();
        s.id = o.optString("id", s.id);
        s.name = o.optString("name", "");
        s.type = o.optString("type", TYPE_XTREAM);
        s.host = o.optString("host", "");
        s.username = o.optString("username", "");
        s.password = o.optString("password", "");
        s.m3uUrl = o.optString("m3uUrl", "");
        s.epgUrl = o.optString("epgUrl", "");
        s.enabled = o.optBoolean("enabled", true);
        s.priority = o.optInt("priority", 0);
        s.preferHls = o.optBoolean("preferHls", true);
        s.userAgent = o.optString("userAgent", "");
        return s;
    }
}
