package com.wm.streamhub.model;

import org.json.JSONException;
import org.json.JSONObject;

/** A playable entry: live channel, movie, or series episode. */
public class StreamItem {

    public static final int KIND_LIVE = 0;
    public static final int KIND_VOD = 1;
    public static final int KIND_SERIES = 2;

    public String id = "";
    public String name = "";
    public String logo = "";
    public String categoryId = "";
    public String categoryName = "";
    public int kind = KIND_LIVE;
    public String serverId = "";
    public String serverName = "";
    /** Direct URL for M3U entries; built on demand for Xtream. */
    public String directUrl = "";
    public String containerExtension = "";
    public String epgChannelId = "";
    public int number = 0;

    public StreamItem() {
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (categoryName != null && !categoryName.isEmpty()) sb.append(categoryName);
        if (serverName != null && !serverName.isEmpty()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(serverName);
        }
        return sb.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("logo", logo);
        o.put("categoryId", categoryId);
        o.put("categoryName", categoryName);
        o.put("kind", kind);
        o.put("serverId", serverId);
        o.put("serverName", serverName);
        o.put("directUrl", directUrl);
        o.put("containerExtension", containerExtension);
        o.put("epgChannelId", epgChannelId);
        o.put("number", number);
        return o;
    }

    public static StreamItem fromJson(JSONObject o) {
        StreamItem s = new StreamItem();
        s.id = o.optString("id", "");
        s.name = o.optString("name", "");
        s.logo = o.optString("logo", "");
        s.categoryId = o.optString("categoryId", "");
        s.categoryName = o.optString("categoryName", "");
        s.kind = o.optInt("kind", KIND_LIVE);
        s.serverId = o.optString("serverId", "");
        s.serverName = o.optString("serverName", "");
        s.directUrl = o.optString("directUrl", "");
        s.containerExtension = o.optString("containerExtension", "");
        s.epgChannelId = o.optString("epgChannelId", "");
        s.number = o.optInt("number", 0);
        return s;
    }

    public String favKey() {
        return kind + "|" + serverId + "|" + id + "|" + name;
    }
}
