package com.wm.streamhub.model;

import org.json.JSONException;
import org.json.JSONObject;

public class ChatMessage {
    public String id = "";
    public String deviceId = "";
    public String sender = "client";   // "client" or "support"
    public String body = "";
    public long createdAt = System.currentTimeMillis();
    public boolean pending = false;
    public boolean failed = false;

    public boolean isMine() {
        return "client".equals(sender);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("deviceId", deviceId);
        o.put("sender", sender);
        o.put("body", body);
        o.put("createdAt", createdAt);
        o.put("pending", pending);
        o.put("failed", failed);
        return o;
    }

    public static ChatMessage fromJson(JSONObject o) {
        ChatMessage m = new ChatMessage();
        m.id = o.optString("id", "");
        m.deviceId = o.optString("deviceId", "");
        m.sender = o.optString("sender", "client");
        m.body = o.optString("body", "");
        m.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        m.pending = o.optBoolean("pending", false);
        m.failed = o.optBoolean("failed", false);
        return m;
    }
}
